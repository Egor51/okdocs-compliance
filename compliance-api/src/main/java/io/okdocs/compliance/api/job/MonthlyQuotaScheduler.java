package io.okdocs.compliance.api.job;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.service.ScanBalanceService;
import io.okdocs.compliance.contracts.enums.UserPlan;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Сброс месячной квоты сканов (§4.6). Безопасен при нескольких репликах: атомарный claim
 * ({@code claimDueForRenewal} одним UPDATE...RETURNING) сдвигает {@code plan_renews_at} и возвращает
 * только реально захваченных юзеров — двойного PLAN_GRANT не возникает. Claim и начисление квоты
 * идут в одной транзакции: падение после claim откатит и сдвиг срока.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyQuotaScheduler {

    private static final Duration PERIOD = Duration.ofDays(30);

    private final AppUserRepository userRepository;
    private final ScanBalanceService balanceService;
    private final ComplianceApiProperties properties;

    @Scheduled(fixedDelayString = "${compliance.quota.scheduler-interval-ms:3600000}")
    @Transactional
    public void renewQuotas() {
        Instant now = Instant.now();
        List<Object[]> claimed = userRepository.claimDueForRenewal(now, now.plus(PERIOD));
        if (claimed.isEmpty()) {
            return;
        }
        log.info("Месячный сброс квоты для {} юзеров", claimed.size());
        for (Object[] row : claimed) {
            Long userId = ((Number) row[0]).longValue();
            UserPlan plan = UserPlan.valueOf((String) row[1]);
            balanceService.grantMonthly(userId, properties.plan().quotaFor(plan));
        }
    }
}
