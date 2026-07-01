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

import java.time.Instant;
import java.util.List;

/**
 * Завершение истёкших платных тарифов PRO/BUSINESS (docs/PLAN-payments.md, Этап 2).
 * <p>
 * Модель non-recurring: платный тариф = разово купленные 30 дней. Job НЕ продлевает тариф (иначе один
 * платёж давал бы квоту вечно — revenue leak), а по истечении {@code plan_renews_at} переводит юзера на
 * FREE и выдаёт FREE-квоту. Продление — только новой оплатой.
 * <p>
 * Безопасен при нескольких репликах: атомарный claim ({@code claimExpiredPaidPlans} одним
 * UPDATE...RETURNING) переводит на FREE и обнуляет срок, возвращая только реально захваченных —
 * двойного downgrade не возникает. Claim и начисление квоты идут в одной транзакции: падение после
 * claim откатит и downgrade.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyQuotaScheduler {

    private final AppUserRepository userRepository;
    private final ScanBalanceService balanceService;
    private final ComplianceApiProperties properties;

    @Scheduled(fixedDelayString = "${compliance.quota.scheduler-interval-ms:3600000}")
    @Transactional
    public void expirePaidPlans() {
        List<Long> expired = userRepository.claimExpiredPaidPlans(Instant.now());
        if (expired.isEmpty()) {
            return;
        }
        log.info("Завершение платного тарифа (→ FREE) для {} юзеров", expired.size());
        int freeQuota = properties.plan().quotaFor(UserPlan.FREE);
        for (Long userId : expired) {
            balanceService.grantMonthly(userId, freeQuota);
        }
    }
}
