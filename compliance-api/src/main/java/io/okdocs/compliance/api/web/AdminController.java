package io.okdocs.compliance.api.web;

import io.okdocs.compliance.api.security.CurrentPrincipal;
import io.okdocs.compliance.api.service.AdminService;
import io.okdocs.compliance.contracts.admin.AdminAdjustBalanceRequest;
import io.okdocs.compliance.contracts.admin.AdminAuditLogDto;
import io.okdocs.compliance.contracts.admin.AdminAuditLogResponse;
import io.okdocs.compliance.contracts.admin.AdminBlockUserRequest;
import io.okdocs.compliance.contracts.admin.AdminSetPlanRequest;
import io.okdocs.compliance.contracts.admin.AdminStatsResponse;
import io.okdocs.compliance.contracts.admin.AdminUserDetail;
import io.okdocs.compliance.contracts.admin.AdminUserListResponse;
import io.okdocs.compliance.contracts.enums.UserPlan;
import io.okdocs.compliance.contracts.enums.UserStatus;
import io.okdocs.compliance.persistence.admin.AdminAuditLog;
import io.okdocs.compliance.persistence.admin.AdminAuditLogRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Админ-слой (§4.1, только ADMIN). Мутации логируются в admin_audit_log. */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminService adminService;
    private final AdminAuditLogRepository auditLogRepository;

    @GetMapping("/users")
    public AdminUserListResponse users(@RequestParam(required = false) UserPlan plan,
                                       @RequestParam(required = false) UserStatus status,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return adminService.listUsers(plan, status, pageable(page, size));
    }

    @GetMapping("/users/{id}")
    public AdminUserDetail user(@PathVariable Long id) {
        return adminService.getUser(id);
    }

    @GetMapping("/users/{id}/scans")
    public io.okdocs.compliance.contracts.scan.ScanListResponse userScans(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return adminService.listUserScans(id, pageable(page, size));
    }

    @PostMapping("/users/{id}/balance")
    public ResponseEntity<Void> adjustBalance(@PathVariable Long id,
                                              @Valid @RequestBody AdminAdjustBalanceRequest request) {
        adminService.adjustBalance(CurrentPrincipal.require().userId(), id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/plan")
    public ResponseEntity<Void> setPlan(@PathVariable Long id,
                                        @Valid @RequestBody AdminSetPlanRequest request) {
        adminService.setPlan(CurrentPrincipal.require().userId(), id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/block")
    public ResponseEntity<Void> block(@PathVariable Long id,
                                      @Valid @RequestBody AdminBlockUserRequest request) {
        adminService.blockUser(CurrentPrincipal.require().userId(), id, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public AdminStatsResponse stats() {
        return adminService.stats();
    }

    @GetMapping("/audit")
    public AdminAuditLogResponse audit(@RequestParam(required = false) Long targetUserId,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        var logs = targetUserId != null
                ? auditLogRepository.findByTargetUserIdOrderByCreatedAtDesc(targetUserId, pageable(page, size))
                : auditLogRepository.findAll(PageRequest.of(Math.max(page, 0),
                        Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                        org.springframework.data.domain.Sort.by("createdAt").descending()));
        List<AdminAuditLogDto> items = logs.getContent().stream().map(AdminController::toDto).toList();
        return new AdminAuditLogResponse(items, logs.getNumber(), logs.getSize(), logs.getTotalElements());
    }

    private static PageRequest pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }

    private static AdminAuditLogDto toDto(AdminAuditLog log) {
        return new AdminAuditLogDto(
                log.getId(), log.getAdminUserId(), log.getAction(), log.getTargetUserId(),
                log.getReason(), log.getDetailsJson(), log.getCreatedAt());
    }
}
