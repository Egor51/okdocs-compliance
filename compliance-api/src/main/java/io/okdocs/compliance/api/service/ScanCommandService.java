package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.security.CompliancePrincipal;
import io.okdocs.compliance.messaging.OutboxEventFactory;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.ScanKind;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.enums.ScanTier;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.contracts.event.ScanRequestedEvent;
import io.okdocs.compliance.contracts.exception.AccessDeniedToScanException;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.exception.ScanNotFoundException;
import io.okdocs.compliance.contracts.exception.ScanReportNotReadyException;
import io.okdocs.compliance.contracts.scan.FreeScanRequest;
import io.okdocs.compliance.contracts.scan.PaywallCtaDto;
import io.okdocs.compliance.contracts.scan.ScanEmailRequest;
import io.okdocs.compliance.contracts.scan.ScanListResponse;
import io.okdocs.compliance.contracts.scan.ScanReportResponse;
import io.okdocs.compliance.contracts.scan.ScanRequest;
import io.okdocs.compliance.contracts.scan.ScanStatusResponse;
import io.okdocs.compliance.persistence.outbox.OutboxEvent;
import io.okdocs.compliance.persistence.outbox.OutboxEventRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanReport;
import io.okdocs.compliance.persistence.scan.ComplianceScanReportRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import io.okdocs.compliance.persistence.scan.ScanEmail;
import io.okdocs.compliance.persistence.scan.ScanEmailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Команды над сканами (§4.2): запуск (rate-limit → списание → запись скана + outbox в одной
 * транзакции), статус, отчёт (с tier-маскировкой), история, сохранение email.
 */
@Service
@RequiredArgsConstructor
public class ScanCommandService {

    private final ComplianceScanRepository scanRepository;
    private final ComplianceScanReportRepository scanReportRepository;
    private final ScanEmailRepository scanEmailRepository;
    private final OutboxEventRepository outboxRepository;
    private final OutboxEventFactory outboxEventFactory;
    private final ScanBalanceService balanceService;
    private final RateLimitService rateLimitService;
    private final UrlValidatorService urlValidator;
    private final ScanMapper scanMapper;
    private final ComplianceApiProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Бесплатный маркетинговый скан ({@code POST /api/free-scans}): {@code FREE_MARKETING}, 1 страница,
     * только STATIC, без списания баланса. Доступен гостям и юзерам. Родителя нет.
     */
    @Transactional
    public ScanStatusResponse startFreeScan(FreeScanRequest request, String ipAddress,
                                            CompliancePrincipal principal) {
        rateLimitService.checkScanAllowed(principal, ipAddress);
        UrlValidatorService.ValidatedUrl validated = urlValidator.validate(request.siteUrl());

        ScanJurisdiction jurisdiction = resolveEnabledJurisdiction(request.jurisdiction());

        ComplianceScan scan = newScan(validated, ipAddress, principal, null,
                ScanKind.FREE_MARKETING, properties.scan().freeMarketingMaxPages(), false, jurisdiction,
                parseLocale(request.locale()));
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

        ScanJurisdiction jurisdiction = resolveEnabledJurisdiction(request.jurisdiction());

        ComplianceScan scan = newScan(validated, ipAddress, principal, parentScanId,
                ScanKind.CABINET_PREMIUM, properties.scan().userMaxPages(), true, jurisdiction,
                parseLocale(request.locale()));

        // Списание баланса — в той же транзакции (oversell ловит @Version; нет баланса → 402).
        balanceService.debit(principal.userId(), scan.getId());

        scanRepository.save(scan);
        publishScanRequested(scan, principal);
        return toStatusResponse(scan);
    }

    /** Сборка строки скана в QUEUED. Режим выполнения (kind/maxPages/dynamicRequired) — здесь. */
    private ComplianceScan newScan(UrlValidatorService.ValidatedUrl validated, String ipAddress,
                                   CompliancePrincipal principal, UUID parentScanId,
                                   ScanKind kind, int maxPages, boolean dynamicRequired,
                                   ScanJurisdiction jurisdiction, String locale) {
        ComplianceScan scan = new ComplianceScan();
        scan.setId(UUID.randomUUID());
        scan.setUserId(principal.userId());
        scan.setJurisdiction(jurisdiction);
        scan.setLocale(locale);
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

        // Снапшот строит worker (premium + free JSON) в той же транзакции, что findings/status.
        // API — чистый passthrough: выбираем нужный JSON по effectiveTier и дописываем paywallCta
        // (product-shell, не compliance-данные) для FREE.
        ComplianceScanReport snapshot = scanReportRepository.findById(scanId).orElse(null);
        if (snapshot != null) {
            return fromSnapshot(scan, snapshot);
        }
        throw new ScanReportNotReadyException(scanId);
    }

    private ScanReportResponse fromSnapshot(ComplianceScan scan, ComplianceScanReport snapshot) {
        boolean premium = effectiveTier(scan) == ScanTier.PREMIUM;
        String json = premium ? snapshot.getPremiumReportJson() : snapshot.getFreeReportJson();
        ScanReportResponse report = deserializeReport(scan.getId(), json);
        // jurisdiction — всегда из живой сущности: снапшоты до добавления поля его не содержат.
        return withServingFields(report, scan, premium ? report.paywallCta() : paywallCta());
    }

    private ScanReportResponse deserializeReport(UUID scanId, String json) {
        try {
            return objectMapper.readValue(json, ScanReportResponse.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Повреждённый снапшот отчёта скана " + scanId, e);
        }
    }

    /**
     * CABINET_PREMIUM оплачен списанием баланса при запуске, поэтому отчёт premium даже если старая
     * строка ещё несёт исторический дефолт tier=FREE. Дешёвая не-доменная проверка двух полей —
     * остаётся в API как выбор, какой снапшот считать premium.
     */
    private static ScanTier effectiveTier(ComplianceScan scan) {
        if (scan.getTier() == ScanTier.PREMIUM || scan.getKind() == ScanKind.CABINET_PREMIUM) {
            return ScanTier.PREMIUM;
        }
        return ScanTier.FREE;
    }

    private PaywallCtaDto paywallCta() {
        var cta = properties.paywallCta();
        if (cta == null) {
            return null;
        }
        return new PaywallCtaDto(cta.title(), cta.text(), cta.actionUrl());
    }

    /** Поля, которые API дописывает к снапшоту при выдаче: jurisdiction из сущности + paywallCta. */
    private static ScanReportResponse withServingFields(ScanReportResponse report, ComplianceScan scan,
                                                        PaywallCtaDto cta) {
        return new ScanReportResponse(
                report.id(), report.siteUrl(), report.siteDomain(), scan.getJurisdiction(),
                report.status(), report.score(), report.tier(), report.parentScanId(),
                report.summary(), report.findings(), report.diagnostics(), report.quality(), cta,
                report.durationMs(), report.createdAt(), report.finishedAt());
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
    static ScanJurisdiction parseJurisdiction(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ComplianceValidationException("Не указана юрисдикция скана");
        }
        try {
            return ScanJurisdiction.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ComplianceValidationException("Неизвестная юрисдикция скана: " + raw);
        }
    }

    /** Поддерживаемые локали отчёта (§ PLAN-evidence-localization). Дефолт — RU. */
    private static final java.util.Set<String> SUPPORTED_LOCALES =
            java.util.Set.of("ru", "en", "de", "fr", "es");
    static final String DEFAULT_LOCALE = "ru";

    /**
     * Нормализует locale отчёта: пусто → дефолт {@link #DEFAULT_LOCALE}; неизвестный → дефолт (мягко,
     * не 400 — locale косметический, не должен ронять скан). Ось локализации evidence/message, НЕ
     * jurisdiction. Хранится в строке скана, worker рендерит по нему.
     */
    static String parseLocale(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return SUPPORTED_LOCALES.contains(normalized) ? normalized : DEFAULT_LOCALE;
    }

    /**
     * Бизнес-проверка доступности юрисдикции (§ Этап 13): юрисдикция синтаксически валидна, но набор
     * правил для неё может быть ещё не готов ({@code enabled-jurisdictions}). Защита от «пустого
     * идеального отчёта»: скан по неподдерживаемой юрисдикции → 400, а не пустой PASS-отчёт. Отделено
     * от {@link #parseJurisdiction} (синтаксис) — единая точка для scan и checkout.
     */
    static void assertJurisdictionEnabled(ScanJurisdiction jurisdiction,
                                          java.util.Set<ScanJurisdiction> enabled) {
        if (!enabled.contains(jurisdiction)) {
            throw new ComplianceValidationException(
                    "Юрисдикция пока не поддерживается: " + jurisdiction);
        }
    }

    /** Разбор + проверка доступности одним вызовом — для scan и checkout. */
    static ScanJurisdiction parseAndAssertEnabled(String raw, java.util.Set<ScanJurisdiction> enabled) {
        ScanJurisdiction jurisdiction = parseJurisdiction(raw);
        assertJurisdictionEnabled(jurisdiction, enabled);
        return jurisdiction;
    }

    /**
     * Instance-обёртка над {@link #parseAndAssertEnabled} с {@code enabled-jurisdictions} из
     * properties — единая точка для checkout (у которого нет своих properties).
     */
    public ScanJurisdiction resolveEnabledJurisdiction(String raw) {
        // Парсим (синтаксис) ДО чтения properties: неизвестная юрисдикция → 400 без обращения к
        // enabled-jurisdictions (важно и для тестов с замоканными properties).
        ScanJurisdiction jurisdiction = parseJurisdiction(raw);
        assertJurisdictionEnabled(jurisdiction, properties.scan().enabledJurisdictions());
        return jurisdiction;
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
