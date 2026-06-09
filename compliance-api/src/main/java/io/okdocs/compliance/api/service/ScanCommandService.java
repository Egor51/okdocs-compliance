package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.security.CompliancePrincipal;
import io.okdocs.compliance.messaging.OutboxEventFactory;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.ScanKind;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.enums.ScanTier;
import io.okdocs.compliance.contracts.event.ScanRequestedEvent;
import io.okdocs.compliance.contracts.exception.AccessDeniedToScanException;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.exception.ScanNotFoundException;
import io.okdocs.compliance.contracts.scan.FreeScanRequest;
import io.okdocs.compliance.contracts.scan.ScanEmailRequest;
import io.okdocs.compliance.contracts.scan.ScanListResponse;
import io.okdocs.compliance.contracts.scan.ScanReportResponse;
import io.okdocs.compliance.contracts.scan.ScanRequest;
import io.okdocs.compliance.contracts.scan.ScanStatusResponse;
import io.okdocs.compliance.persistence.outbox.OutboxEvent;
import io.okdocs.compliance.persistence.outbox.OutboxEventRepository;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import io.okdocs.compliance.persistence.scan.ComplianceFindingRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import io.okdocs.compliance.persistence.scan.ScanEmail;
import io.okdocs.compliance.persistence.scan.ScanEmailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Команды над сканами (§4.2): запуск (rate-limit → списание → запись скана + outbox в одной
 * транзакции), статус, отчёт (с tier-маскировкой), история, сохранение email.
 */
@Service
@RequiredArgsConstructor
public class ScanCommandService {

    private final ComplianceScanRepository scanRepository;
    private final ComplianceFindingRepository findingRepository;
    private final ScanEmailRepository scanEmailRepository;
    private final OutboxEventRepository outboxRepository;
    private final OutboxEventFactory outboxEventFactory;
    private final ScanBalanceService balanceService;
    private final RateLimitService rateLimitService;
    private final UrlValidatorService urlValidator;
    private final ScanMapper scanMapper;
    private final ScanReportAssembler reportAssembler;
    private final ComplianceApiProperties properties;

    /**
     * Бесплатный маркетинговый скан ({@code POST /api/free-scans}): {@code FREE_MARKETING}, 1 страница,
     * только STATIC, без списания баланса. Доступен гостям и юзерам. Родителя нет.
     */
    @Transactional
    public ScanStatusResponse startFreeScan(FreeScanRequest request, String ipAddress,
                                            CompliancePrincipal principal) {
        rateLimitService.checkScanAllowed(principal, ipAddress);
        UrlValidatorService.ValidatedUrl validated = urlValidator.validate(request.siteUrl());

        ScanJurisdiction jurisdiction = parseJurisdiction(request.jurisdiction());

        ComplianceScan scan = newScan(validated, ipAddress, principal, null,
                ScanKind.FREE_MARKETING, properties.scan().freeMarketingMaxPages(), false, jurisdiction);
        scanRepository.save(scan);
        publishScanRequested(scan, principal);
        return toStatusResponse(scan);
    }

    /**
     * Полноценный premium-скан кабинета ({@code POST /api/cabinet/scans}): {@code CABINET_PREMIUM},
     * полный crawl, STATIC + DYNAMIC (dynamic required). Только для USER; списывает 1 кредит баланса
     * (нет баланса → 402 через {@code InsufficientScanBalanceException}). При FAILED worker вернёт
     * кредит (refund по {@code ScanFailedEvent}).
     */
    @Transactional
    public ScanStatusResponse startCabinetScan(ScanRequest request, String ipAddress,
                                               CompliancePrincipal principal) {
        if (!principal.isUser()) {
            throw new AccessDeniedToScanException(null);
        }
        rateLimitService.checkScanAllowed(principal, ipAddress);
        UrlValidatorService.ValidatedUrl validated = urlValidator.validate(request.siteUrl());
        UUID parentScanId = resolveParent(request.parentScanId(), validated.domain(), principal);

        ScanJurisdiction jurisdiction = parseJurisdiction(request.jurisdiction());

        ComplianceScan scan = newScan(validated, ipAddress, principal, parentScanId,
                ScanKind.CABINET_PREMIUM, properties.scan().userMaxPages(), true, jurisdiction);

        // Списание баланса — в той же транзакции (oversell ловит @Version; нет баланса → 402).
        balanceService.debit(principal.userId(), scan.getId());

        scanRepository.save(scan);
        publishScanRequested(scan, principal);
        return toStatusResponse(scan);
    }

    /** Сборка строки скана в QUEUED. Режим выполнения (kind/maxPages/dynamicRequired) — здесь. */
    private ComplianceScan newScan(UrlValidatorService.ValidatedUrl validated, String ipAddress,
                                   CompliancePrincipal principal, UUID parentScanId,
                                   ScanKind kind, int maxPages, boolean dynamicRequired, ScanJurisdiction jurisdiction) {
        ComplianceScan scan = new ComplianceScan();
        scan.setId(UUID.randomUUID());
        scan.setUserId(principal.userId());
        scan.setJurisdiction(jurisdiction);
        scan.setGuestId(principal.guestId());
        scan.setParentScanId(parentScanId);
        scan.setIpAddress(ipAddress);
        scan.setStatus(ScanStatus.QUEUED);
        scan.setSiteUrl(validated.normalizedUrl());
        scan.setSiteDomain(validated.domain());
        scan.setProgressStep("Ожидание");
        scan.setProgressPct(0);
        scan.setKind(kind);
        scan.setTier(kind == ScanKind.CABINET_PREMIUM ? ScanTier.PREMIUM : ScanTier.FREE);
        scan.setMaxPages(maxPages);
        scan.setDynamicRequired(dynamicRequired);
        return scan;
    }

    /** Transactional outbox: событие-команда «обработай scanId» в той же транзакции, что и скан. */
    private void publishScanRequested(ComplianceScan scan, CompliancePrincipal principal) {
        ScanRequestedEvent event = new ScanRequestedEvent(
                UUID.randomUUID(), 1, scan.getId(), principal.userId(), principal.guestId(),
                scan.getSiteUrl(), Instant.now());
        OutboxEvent outbox = outboxEventFactory.create(
                scan.getId(), properties.kafka().topic().scanRequested(), scan.getId().toString(), event);
        outboxRepository.save(outbox);
    }

    @Transactional(readOnly = true)
    public ScanStatusResponse getStatus(UUID scanId, CompliancePrincipal principal) {
        return toStatusResponse(loadOwned(scanId, principal));
    }

    @Transactional(readOnly = true)
    public ScanReportResponse getReport(UUID scanId, CompliancePrincipal principal) {
        ComplianceScan scan = loadOwned(scanId, principal);
        List<ComplianceFinding> findings = findingRepository.findByScanIdOrderByCreatedAtAsc(scanId);
        return reportAssembler.assemble(scan, findings);
    }

    /** История сканов юзера с фильтрами domain/status (§2.2). Только для USER. */
    @Transactional(readOnly = true)
    public ScanListResponse listScans(Long userId, String domain, ScanStatus status, Pageable pageable) {
        Page<ComplianceScan> page = scanRepository.searchByUser(userId, blankToNull(domain), status, pageable);
        return new ScanListResponse(
                scanMapper.toListItems(page.getContent()),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements());
    }

    @Transactional
    public void saveEmail(UUID scanId, ScanEmailRequest request, String ipAddress,
                          CompliancePrincipal principal) {
        ComplianceScan scan = loadOwned(scanId, principal);
        if (!request.consentToProcessing()) {
            throw new ComplianceValidationException("Требуется согласие на обработку персональных данных");
        }
        ScanEmail email = new ScanEmail();
        email.setScanId(scan.getId());
        email.setEmail(request.email());
        email.setConsentToProcessing(request.consentToProcessing());
        email.setConsentToMarketing(request.consentToMarketing());
        email.setConsentIp(ipAddress);
        email.setConsentAt(Instant.now());
        scanEmailRepository.save(email);

        scan.setBuyerEmail(request.email());
        scanRepository.save(scan);
    }

    /**
     * Строгий разбор юрисдикции из запроса в {@link ScanJurisdiction}: «по какому закону проверяем».
     * Невалидное/пустое значение → 400 ({@link ComplianceValidationException}), без неявного дефолта —
     * выбор юрисдикции явно делает фронт (от неё зависят набор правил и тариф).
     */
    private static ScanJurisdiction parseJurisdiction(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ComplianceValidationException("Не указана юрисдикция скана");
        }
        try {
            return ScanJurisdiction.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ComplianceValidationException("Неизвестная юрисдикция скана: " + raw);
        }
    }

    private UUID resolveParent(UUID parentScanId, String domain, CompliancePrincipal principal) {
        if (parentScanId == null) {
            return null;
        }
        ComplianceScan parent = scanRepository.findById(parentScanId)
                .orElseThrow(() -> new ScanNotFoundException(parentScanId));
        assertOwner(parent, principal);
        if (!parent.getSiteDomain().equalsIgnoreCase(domain)) {
            throw new ComplianceValidationException("Домен повторной проверки не совпадает с родительским сканом");
        }
        return parent.getId();
    }

    private ComplianceScan loadOwned(UUID scanId, CompliancePrincipal principal) {
        ComplianceScan scan = scanRepository.findById(scanId)
                .orElseThrow(() -> new ScanNotFoundException(scanId));
        assertOwner(scan, principal);
        return scan;
    }

    /** Owner-check (§4.3): userId→userId, иначе guestId→guestId. ADMIN обходит через свой слой. */
    private void assertOwner(ComplianceScan scan, CompliancePrincipal principal) {
        if (scan.getUserId() != null) {
            if (!scan.getUserId().equals(principal.userId())) {
                throw new AccessDeniedToScanException(scan.getId());
            }
        } else if (scan.getGuestId() == null || !scan.getGuestId().equals(principal.guestId())) {
            throw new AccessDeniedToScanException(scan.getId());
        }
    }

    private ScanStatusResponse toStatusResponse(ComplianceScan scan) {
        String reportUrl = scan.getStatus().isTerminal() && scan.getStatus() != ScanStatus.FAILED
                ? "/api/compliance-scans/" + scan.getId() + "/report"
                : null;
        return new ScanStatusResponse(
                scan.getId(),
                scan.getStatus(),
                scan.getProgressStep(),
                scan.getProgressPct(),
                reportUrl,
                scan.getErrorMessage());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
