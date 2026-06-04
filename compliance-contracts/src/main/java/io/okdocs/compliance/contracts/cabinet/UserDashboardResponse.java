package io.okdocs.compliance.contracts.cabinet;

import io.okdocs.compliance.contracts.auth.UserProfileDto;
import io.okdocs.compliance.contracts.enums.UserPlan;
import io.okdocs.compliance.contracts.scan.ScanListItemDto;

import java.time.Instant;
import java.util.List;

/** Дашборд кабинета: профиль + тариф + баланс + последние сканы (read-модель). */
public record UserDashboardResponse(
        UserProfileDto user,
        UserPlan plan,
        Instant planRenewsAt,
        ScanBalanceDto balance,
        long totalScans,
        List<ScanListItemDto> recentScans
) {
}
