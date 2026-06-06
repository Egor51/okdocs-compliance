package io.okdocs.compliance.persistence.auth;

import io.okdocs.compliance.contracts.enums.UserPlan;
import io.okdocs.compliance.contracts.enums.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /** Список юзеров для админки с опциональными фильтрами plan/status (§4.1). */
    @Query("""
            SELECT u FROM AppUser u
            WHERE (:plan IS NULL OR u.plan = :plan)
              AND (:status IS NULL OR u.status = :status)
            ORDER BY u.createdAt DESC
            """)
    Page<AppUser> search(@Param("plan") UserPlan plan,
                         @Param("status") UserStatus status,
                         Pageable pageable);

    long countByStatus(UserStatus status);

    /**
     * Атомарный claim юзеров на месячный сброс квоты (§4.6) — безопасен при нескольких репликах.
     * Одним {@code UPDATE ... RETURNING} сдвигает {@code plan_renews_at} на следующий период только
     * для тех, у кого срок наступил. Row-lock сериализует реплики: после коммита первой остальные
     * видят сдвинутый срок и не захватывают строку повторно (нет двойного PLAN_GRANT). Возвращает
     * {@code [id, plan]} захваченных юзеров — по ним scheduler начисляет квоту.
     */
    @Query(value = """
            UPDATE app_users
            SET plan_renews_at = :nextRenewal
            WHERE plan_renews_at IS NOT NULL AND plan_renews_at <= :now
            RETURNING id, plan
            """, nativeQuery = true)
    List<Object[]> claimDueForRenewal(@Param("now") Instant now, @Param("nextRenewal") Instant nextRenewal);

    /** Сводка «кол-во юзеров по тарифу» для статистики админки. */
    @Query("SELECT u.plan, COUNT(u) FROM AppUser u GROUP BY u.plan")
    List<Object[]> countByPlan();
}
