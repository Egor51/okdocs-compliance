package io.okdocs.compliance.persistence.admin;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {

    Page<AdminAuditLog> findByTargetUserIdOrderByCreatedAtDesc(Long targetUserId, Pageable pageable);

    Page<AdminAuditLog> findByAdminUserIdOrderByCreatedAtDesc(Long adminUserId, Pageable pageable);
}
