package io.okdocs.compliance.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.contracts.admin.AdminAdjustBalanceRequest;
import io.okdocs.compliance.contracts.admin.AdminBlockUserRequest;
import io.okdocs.compliance.contracts.admin.AdminSetPlanRequest;
import io.okdocs.compliance.contracts.admin.AdminStatsResponse;
import io.okdocs.compliance.contracts.admin.AdminUserDetail;
import io.okdocs.compliance.contracts.admin.AdminUserListItem;
import io.okdocs.compliance.contracts.admin.AdminUserListResponse;
import io.okdocs.compliance.contracts.enums.AdminActionType;
import io.okdocs.compliance.contracts.enums.UserPlan;
import io.okdocs.compliance.contracts.enums.UserStatus;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.persistence.admin.AdminAuditLog;
import io.okdocs.compliance.persistence.admin.AdminAuditLogRepository;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import io.okdocs.compliance.persistence.billing.ScanBalance;
import io.okdocs.compliance.persistence.billing.ScanBalanceRepository;
import io.okdocs.compliance.persistence.billing.ScanBalanceTransactionRepository;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Админ-операции (§4.2). Каждая мутация пишет {@link AdminAuditLog} в той же транзакции.
 * Контракты admin-request несут {@code userId} в body — он обязан совпасть с path (иначе 400).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private static final int RECENT = 10;

    private final AppUserRepository userRepository;
    private final ScanBalanceRepository balanceRepository;
    private final ScanBalanceTransactionRepository txnRepository;
    private final ComplianceScanRepository scanRepository;
    private final AdminAuditLogRepository auditLogRepository;
    private final ScanBalanceService balanceService;
    private final io.okdocs.compliance.api.service.payment.PaidPlanService paidPlanService;
    private final ScanMapper scanMapper;
    private final BalanceTransactionMapper txnMapper;
    private final ComplianceApiProperties properties;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public AdminUserListResponse listUsers(UserPlan plan, UserStatus status, Pageable pageable) {
        Page<AppUser> page = userRepository.search(plan, status, pageable);
        List<AdminUserListItem> items = page.getContent().stream().map(this::toListItem).toList();
        return new AdminUserListResponse(items, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public AdminUserDetail getUser(Long userId) {
        AppUser user = loadUser(userId);
        var recentTxns = txnRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, RECENT));
        var recentScans = scanRepository.findTop10ByUserIdOrderByCreatedAtDesc(userId);
        return new AdminUserDetail(
                toListItem(user),
                ScanBalanceService.toDto(loadBalance(userId)),
                txnMapper.toDtos(recentTxns.getContent()),
                scanMapper.toListItems(recentScans));
    }

    /** Сканы юзера для админа — обход owner-check (§4.1). */
    @Transactional(readOnly = true)
    public io.okdocs.compliance.contracts.scan.ScanListResponse listUserScans(Long userId, Pageable pageable) {
        var page = scanRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return new io.okdocs.compliance.contracts.scan.ScanListResponse(
                scanMapper.toListItems(page.getContent()),
                page.getNumber(), page.getSize(), page.getTotalElements());
    }

    @Transactional
    public void adjustBalance(Long adminId, Long pathUserId, AdminAdjustBalanceRequest request) {
        requireMatch(pathUserId, request.userId());
        loadUser(pathUserId);
        balanceService.adminAdjust(pathUserId, request.amount(), request.reason());
        audit(adminId, AdminActionType.ADJUST_BALANCE, pathUserId, request.reason(),
                Map.of("amount", request.amount()));
    }

    @Transactional
    public void setPlan(Long adminId, Long pathUserId, AdminSetPlanRequest request) {
        requireMatch(pathUserId, request.userId());
        AppUser user = loadUser(pathUserId);
        UserPlan oldPlan = user.getPlan();
        // Общее ядро активации тарифа (plan + planRenewsAt = now+30d) — то же, что использует
        // платёжная активация PRO/BUSINESS, чтобы две точки выдачи плана не разъезжались.
        paidPlanService.applyPlan(user, request.plan());
        // Обновляем месячную квоту по новому тарифу (админ — прямой grantMonthly, без payment-идемпотентности).
        balanceService.grantMonthly(pathUserId, properties.plan().quotaFor(request.plan()));
        audit(adminId, AdminActionType.SET_PLAN, pathUserId, request.reason(),
                Map.of("oldPlan", oldPlan, "newPlan", request.plan()));
    }

    @Transactional
    public void blockUser(Long adminId, Long pathUserId, AdminBlockUserRequest request) {
        requireMatch(pathUserId, request.userId());
        AppUser user = loadUser(pathUserId);
        UserStatus oldStatus = user.getStatus();
        user.setStatus(request.block() ? UserStatus.BLOCKED : UserStatus.ACTIVE);
        userRepository.save(user);
        AdminActionType action = request.block() ? AdminActionType.BLOCK_USER : AdminActionType.UNBLOCK_USER;
        audit(adminId, action, pathUserId, request.reason(),
                Map.of("oldStatus", oldStatus, "newStatus", user.getStatus()));
    }

    @Transactional(readOnly = true)
    public AdminStatsResponse stats() {
        long total = userRepository.count();
        long blocked = userRepository.countByStatus(UserStatus.BLOCKED);
        long active = userRepository.countByStatus(UserStatus.ACTIVE);
        Instant startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS);
        long scansToday = scanRepository.countByCreatedAtAfter(startOfDay);
        long scansTotal = scanRepository.count();

        Map<UserPlan, Long> byPlan = new EnumMap<>(UserPlan.class);
        for (Object[] row : userRepository.countByPlan()) {
            byPlan.put((UserPlan) row[0], (Long) row[1]);
        }
        return new AdminStatsResponse(total, active, blocked, scansToday, scansTotal, byPlan);
    }

    private AdminUserListItem toListItem(AppUser user) {
        int available = balanceRepository.findByUserId(user.getId())
                .map(ScanBalance::available)
                .orElse(0);
        long totalScans = scanRepository.countByUserId(user.getId());
        return new AdminUserListItem(
                user.getId(), user.getEmail(), user.getName(), user.getPlan(), user.getStatus(),
                available, totalScans, user.getCreatedAt(), user.getLastLoginAt());
    }

    private void audit(Long adminId, AdminActionType action, Long targetUserId, String reason,
                       Map<String, Object> details) {
        AdminAuditLog log = new AdminAuditLog();
        log.setAdminUserId(adminId);
        log.setAction(action);
        log.setTargetUserId(targetUserId);
        log.setReason(reason);
        log.setDetailsJson(serialize(details));
        auditLogRepository.save(log);
    }

    private String serialize(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            log.warn("Не удалось сериализовать details аудита: {}", e.getMessage());
            return null;
        }
    }

    private void requireMatch(Long pathUserId, Long bodyUserId) {
        if (!pathUserId.equals(bodyUserId)) {
            throw new ComplianceValidationException("userId в пути и теле запроса не совпадают");
        }
    }

    private AppUser loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ComplianceValidationException("Пользователь не найден: " + userId));
    }

    private ScanBalance loadBalance(Long userId) {
        return balanceRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Баланс не найден для юзера " + userId));
    }
}
