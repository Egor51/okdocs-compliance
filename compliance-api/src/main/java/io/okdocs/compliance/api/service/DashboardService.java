package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.cabinet.UserDashboardResponse;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-модель кабинета (§4.2): профиль + тариф + баланс + агрегаты по сканам. */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final int RECENT_SCANS = 10;

    private final AppUserRepository userRepository;
    private final ComplianceScanRepository scanRepository;
    private final ScanBalanceService balanceService;
    private final ScanMapper scanMapper;

    @Transactional(readOnly = true)
    public UserDashboardResponse getDashboard(Long userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ComplianceValidationException("Пользователь не найден"));

        Page<ComplianceScan> recent = scanRepository.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(0, RECENT_SCANS));

        return new UserDashboardResponse(
                AuthService.toProfile(user),
                user.getPlan(),
                user.getPlanRenewsAt(),
                balanceService.getBalance(userId),
                recent.getTotalElements(),
                scanMapper.toListItems(recent.getContent()));
    }
}
