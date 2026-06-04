package io.okdocs.compliance.contracts.admin;

import io.okdocs.compliance.contracts.enums.UserPlan;

import java.util.Map;

/** Сводная статистика для админ-дашборда. */
public record AdminStatsResponse(
        long totalUsers,
        long activeUsers,
        long blockedUsers,
        long scansToday,
        long scansTotal,
        Map<UserPlan, Long> usersByPlan
) {
}
