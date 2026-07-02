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
     * Атомарное завершение истёкших платных тарифов (docs/PLAN-payments.md, Этап 2) — безопасно при
     * нескольких репликах. Модель non-recurring: {@code plan_renews_at} — конец оплаченного периода, а
     * НЕ дата автопродления. Одним {@code UPDATE ... RETURNING} переводит на FREE и обнуляет срок только
     * для PRO/BUSINESS с наступившим сроком. Row-lock сериализует реплики: после коммита первой
     * остальные уже видят {@code plan=FREE} (не матчит WHERE) и строку не захватывают повторно —
     * двойного downgrade/PLAN_GRANT нет. Возвращает {@code [id]} завершённых юзеров (им начисляется
     * FREE-квота). Платный тариф НЕ продлевается бесплатно — продление только новой оплатой.
     */
    @Query(value = """
            UPDATE app_users
            SET plan = 'FREE', plan_renews_at = NULL
            WHERE plan IN ('PRO', 'BUSINESS') AND plan_renews_at IS NOT NULL AND plan_renews_at <= :now
            RETURNING id
            """, nativeQuery = true)
    List<Long> claimExpiredPaidPlans(@Param("now") Instant now);

    /** Сводка «кол-во юзеров по тарифу» для статистики админки. */
    @Query("SELECT u.plan, COUNT(u) FROM AppUser u GROUP BY u.plan")
    List<Object[]> countByPlan();
}
