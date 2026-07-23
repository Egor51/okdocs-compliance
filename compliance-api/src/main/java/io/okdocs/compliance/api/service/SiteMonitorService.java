package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.config.MonitoringProperties;
import io.okdocs.compliance.api.security.CompliancePrincipal;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.enums.ScanTier;
import io.okdocs.compliance.contracts.enums.SiteMonitorStatus;
import io.okdocs.compliance.contracts.enums.UserPlan;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.exception.SiteMonitorNotFoundException;
import io.okdocs.compliance.contracts.monitoring.CreateSiteMonitorRequest;
import io.okdocs.compliance.contracts.monitoring.MonitorRunDto;
import io.okdocs.compliance.contracts.monitoring.SiteMonitorDto;
import io.okdocs.compliance.contracts.monitoring.UpdateSiteMonitorRequest;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import io.okdocs.compliance.persistence.monitoring.MonitorRun;
import io.okdocs.compliance.persistence.monitoring.MonitorRunRepository;
import io.okdocs.compliance.persistence.monitoring.SiteMonitor;
import io.okdocs.compliance.persistence.monitoring.SiteMonitorRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SiteMonitorService {

    private final SiteMonitorRepository monitorRepository;
    private final MonitorRunRepository runRepository;
    private final ComplianceScanRepository scanRepository;
    private final AppUserRepository userRepository;
    private final UrlValidatorService urlValidator;
    private final ComplianceApiProperties properties;
    private final MonitoringProperties monitoringProperties;
    private final SiteMonitorExecutionService executionService;

    @Transactional
    public SiteMonitorDto create(CreateSiteMonitorRequest request, CompliancePrincipal principal) {
        Long userId = requireUser(principal);
        AppUser user = requireMonitoringPlan(userId);
        int maxMonitors = monitoringProperties.maxMonitorsFor(user.getPlan());
        if (monitorRepository.countByUserId(userId) >= maxMonitors) {
            throw new ComplianceValidationException(
                    "Достигнут лимит сайтов для мониторинга на текущем тарифе");
        }

        UrlValidatorService.ValidatedUrl validated = urlValidator.validate(request.siteUrl());
        ScanJurisdiction jurisdiction = parseJurisdiction(request.jurisdiction());
        ZoneId zone = parseZone(request.timezone());
        String locale = parseLocale(request.locale());
        ComplianceScan baseline = validateBaseline(
                request.baselineScanId(), userId, validated.domain(), jurisdiction);

        if (monitorRepository.existsByUserIdAndSiteDomainAndJurisdiction(
                userId, validated.domain(), jurisdiction)) {
            throw new ComplianceValidationException("Этот сайт уже находится под мониторингом");
        }

        Instant now = Instant.now();
        SiteMonitor monitor = new SiteMonitor();
        monitor.setUserId(userId);
        monitor.setSiteUrl(validated.normalizedUrl());
        monitor.setSiteDomain(validated.domain());
        monitor.setJurisdiction(jurisdiction);
        monitor.setLocale(locale);
        monitor.setStatus(SiteMonitorStatus.ACTIVE);
        monitor.setIntervalDays(request.intervalDays());
        monitor.setTimezone(zone.getId());
        monitor.setNotificationsEnabled(request.notificationsEnabled());
        if (baseline != null) {
            monitor.setLastScanId(baseline.getId());
            monitor.setLastScore(baseline.getScore());
            monitor.setLastRunAt(baseline.getFinishedAt());
            monitor.setNextRunAt(nextAfter(now, request.intervalDays(), zone));
        } else {
            monitor.setNextRunAt(now);
        }

        try {
            return toDto(monitorRepository.saveAndFlush(monitor));
        } catch (DataIntegrityViolationException e) {
            throw new ComplianceValidationException("Этот сайт уже находится под мониторингом");
        }
    }

    @Transactional(readOnly = true)
    public List<SiteMonitorDto> list(CompliancePrincipal principal) {
        return monitorRepository.findByUserIdOrderByCreatedAtDesc(requireUser(principal)).stream()
                .map(SiteMonitorService::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public SiteMonitorDto get(UUID id, CompliancePrincipal principal) {
        return toDto(loadOwned(id, requireUser(principal)));
    }

    @Transactional(readOnly = true)
    public List<MonitorRunDto> runs(UUID id, CompliancePrincipal principal) {
        SiteMonitor monitor = loadOwned(id, requireUser(principal));
        return runRepository.findTop50ByMonitorIdOrderByCreatedAtDesc(monitor.getId()).stream()
                .map(SiteMonitorService::toDto)
                .toList();
    }

    @Transactional
    public SiteMonitorDto update(UUID id, UpdateSiteMonitorRequest request,
                                 CompliancePrincipal principal) {
        SiteMonitor monitor = loadOwned(id, requireUser(principal));
        ZoneId zone = parseZone(monitor.getTimezone());
        monitor.setIntervalDays(request.intervalDays());
        monitor.setNotificationsEnabled(request.notificationsEnabled());
        monitor.setNextRunAt(nextAfter(Instant.now(), request.intervalDays(), zone));
        return toDto(monitorRepository.save(monitor));
    }

    @Transactional
    public SiteMonitorDto pause(UUID id, CompliancePrincipal principal) {
        SiteMonitor monitor = loadOwned(id, requireUser(principal));
        monitor.setStatus(SiteMonitorStatus.PAUSED);
        return toDto(monitorRepository.save(monitor));
    }

    @Transactional
    public SiteMonitorDto resume(UUID id, CompliancePrincipal principal) {
        Long userId = requireUser(principal);
        requireMonitoringPlan(userId);
        SiteMonitor monitor = loadOwned(id, userId);
        monitor.setStatus(SiteMonitorStatus.ACTIVE);
        monitor.setNextRunAt(nextAfter(
                Instant.now(), monitor.getIntervalDays(), parseZone(monitor.getTimezone())));
        return toDto(monitorRepository.save(monitor));
    }

    public SiteMonitorDto runNow(UUID id, CompliancePrincipal principal) {
        Long userId = requireUser(principal);
        requireMonitoringPlan(userId);
        executionService.executeManual(id, userId);
        return get(id, principal);
    }

    @Transactional
    public void delete(UUID id, CompliancePrincipal principal) {
        SiteMonitor monitor = loadOwned(id, requireUser(principal));
        if (runRepository.existsByMonitorIdAndStatus(
                monitor.getId(), io.okdocs.compliance.contracts.enums.MonitorRunStatus.RUNNING)) {
            throw new ComplianceValidationException(
                    "Нельзя удалить мониторинг во время выполняющейся проверки");
        }
        monitorRepository.delete(monitor);
    }

    private AppUser requireMonitoringPlan(Long userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ComplianceValidationException("Пользователь не найден"));
        if (user.getPlan() == UserPlan.FREE || user.getPlanRenewsAt() == null
                || !user.getPlanRenewsAt().isAfter(Instant.now())) {
            throw new ComplianceValidationException(
                    "Мониторинг доступен только при активном тарифе PRO или BUSINESS");
        }
        return user;
    }

    private ComplianceScan validateBaseline(UUID baselineId, Long userId, String domain,
                                            ScanJurisdiction jurisdiction) {
        if (baselineId == null) {
            return null;
        }
        ComplianceScan scan = scanRepository.findById(baselineId)
                .orElseThrow(() -> new ComplianceValidationException("Базовый отчёт не найден"));
        if (!userId.equals(scan.getUserId()) || scan.getTier() != ScanTier.PREMIUM
                || !(scan.getStatus() == ScanStatus.COMPLETED || scan.getStatus() == ScanStatus.PARTIAL)
                || !domain.equalsIgnoreCase(scan.getSiteDomain())
                || jurisdiction != scan.getJurisdiction()) {
            throw new ComplianceValidationException("Базовый отчёт не подходит для этого мониторинга");
        }
        return scan;
    }

    private ScanJurisdiction parseJurisdiction(String value) {
        try {
            ScanJurisdiction jurisdiction = ScanJurisdiction.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (!properties.scan().enabledJurisdictions().contains(jurisdiction)) {
                throw new IllegalArgumentException();
            }
            return jurisdiction;
        } catch (IllegalArgumentException e) {
            throw new ComplianceValidationException("Неподдерживаемая юрисдикция");
        }
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (DateTimeException e) {
            throw new ComplianceValidationException("Некорректный часовой пояс");
        }
    }

    private static String parseLocale(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("ru") && !normalized.equals("en")) {
            throw new ComplianceValidationException("Неподдерживаемый язык отчёта");
        }
        return normalized;
    }

    private static Instant nextAfter(Instant from, int intervalDays, ZoneId zone) {
        return ZonedDateTime.ofInstant(from, zone).plusDays(intervalDays).toInstant();
    }

    private static Long requireUser(CompliancePrincipal principal) {
        if (principal == null || !principal.isUser()) {
            throw new ComplianceValidationException("Требуется авторизация пользователя");
        }
        return principal.userId();
    }

    private SiteMonitor loadOwned(UUID id, Long userId) {
        return monitorRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new SiteMonitorNotFoundException(id));
    }

    static SiteMonitorDto toDto(SiteMonitor monitor) {
        return new SiteMonitorDto(
                monitor.getId(), monitor.getSiteUrl(), monitor.getSiteDomain(),
                monitor.getJurisdiction(), monitor.getStatus(), monitor.getIntervalDays(),
                monitor.getTimezone(), monitor.isNotificationsEnabled(), monitor.getLastScanId(),
                monitor.getLastScore(), monitor.getLastRunAt(), monitor.getNextRunAt(),
                monitor.getCreatedAt());
    }

    static MonitorRunDto toDto(MonitorRun run) {
        return new MonitorRunDto(
                run.getId(), run.getMonitorId(), run.getScanId(), run.getTrigger(), run.getStatus(),
                run.getScheduledFor(), run.getPreviousScore(), run.getCurrentScore(),
                run.getNewFindings(), run.getResolvedFindings(), run.getErrorMessage(),
                run.failure(),
                run.getCreatedAt(), run.getFinishedAt());
    }
}
