package io.okdocs.compliance.contracts.admin;

import io.okdocs.compliance.contracts.cabinet.BalanceTransactionDto;
import io.okdocs.compliance.contracts.cabinet.ScanBalanceDto;
import io.okdocs.compliance.contracts.scan.ScanListItemDto;

import java.util.List;

/** Детали юзера для админа: базовая строка + баланс + последние транзакции и сканы. */
public record AdminUserDetail(
        AdminUserListItem user,
        ScanBalanceDto balance,
        List<BalanceTransactionDto> recentTransactions,
        List<ScanListItemDto> recentScans
) {
}
