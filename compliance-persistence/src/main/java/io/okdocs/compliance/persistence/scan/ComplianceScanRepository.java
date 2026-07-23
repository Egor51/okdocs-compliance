package io.okdocs.compliance.persistence.scan;

import io.okdocs.compliance.contracts.enums.ScanKind;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ComplianceScanRepository extends JpaRepository<ComplianceScan, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ComplianceScan s WHERE s.id = :scanId")
    java.util.Optional<ComplianceScan> findByIdForUpdate(@Param("scanId") UUID scanId);

    Page<ComplianceScan> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<ComplianceScan> findByGuestIdOrderByCreatedAtDesc(UUID guestId, Pageable pageable);

    List<ComplianceScan> findByStatus(ScanStatus status);

    /** Rate limit: сколько сканов с этого IP за окно. */
    List<ComplianceScan> findByIpAddressAndCreatedAtAfter(String ipAddress, Instant after);

    /** Статистика админки: сканов за период (scansToday) и всего. */
    long countByCreatedAtAfter(Instant after);

    /** Кол-во сканов юзера (для админ-детали / списка). */
    long countByUserId(Long userId);

    /** Последние сканы юзера (админ-деталь, обход owner-check). */
    java.util.List<ComplianceScan> findTop10ByUserIdOrderByCreatedAtDesc(Long userId);

    /** Reaper зависших сканов (§5.3): статус в работе + давно не обновлялся. */
    List<ComplianceScan> findByStatusInAndUpdatedAtBefore(Collection<ScanStatus> statuses, Instant cutoff);

    /**
     * Atomic ownership claim. Only one Kafka delivery can move a scan from QUEUED to CRAWLING.
     * Incrementing the optimistic version fences entities loaded before the claim.
     */
    @Modifying
    @Query("""
            UPDATE ComplianceScan s
            SET s.status = io.okdocs.compliance.contracts.enums.ScanStatus.CRAWLING,
                s.startedAt = COALESCE(s.startedAt, :now),
                s.updatedAt = :now,
                s.version = s.version + 1
            WHERE s.id = :scanId
              AND s.status = io.okdocs.compliance.contracts.enums.ScanStatus.QUEUED
            """)
    int claimQueued(@Param("scanId") UUID scanId, @Param("now") Instant now);

    /**
     * Backfill отчётных снапшотов (этап 3.5): terminal-сканы с findings ({@code COMPLETED}/{@code PARTIAL}),
     * у которых ещё нет строки в {@code compliance_scan_reports}. {@code FAILED} исключён — у него нет
     * findings и отчёт не строится. Пачками (через {@link Pageable}), чтобы джоб не держал длинную
     * транзакцию и не тянул всё в память.
     */
    @Query("""
            SELECT s FROM ComplianceScan s
            WHERE s.status IN :statuses
              AND NOT EXISTS (SELECT 1 FROM ComplianceScanReport r WHERE r.scanId = s.id)
            ORDER BY s.createdAt ASC
            """)
    List<ComplianceScan> findTerminalWithoutReport(@Param("statuses") Collection<ScanStatus> statuses,
                                                    Pageable pageable);

    /**
     * История кабинета с опциональными фильтрами domain (ILIKE substring) и status (§4.1).
     * <p>
     * {@code cast(:domain as string)} обязателен: при {@code domain = null} Postgres не может вывести
     * тип bind-параметра внутри {@code lower(concat(...))} и трактует его как {@code bytea}, из-за чего
     * планировщик падает с {@code lower(bytea) does not exist} (short-circuit {@code IS NULL} не спасает —
     * выражение типизируется на этапе планирования). Явный cast в text фиксирует тип.
     */
    @Query("""
            SELECT s FROM ComplianceScan s
            WHERE s.userId = :userId
              AND (:domain IS NULL OR lower(s.siteDomain) LIKE lower(concat('%', cast(:domain as string), '%')))
              AND (:status IS NULL OR s.status = :status)
            ORDER BY s.createdAt DESC
            """)
    Page<ComplianceScan> searchByUser(@Param("userId") Long userId,
                                      @Param("domain") String domain,
                                      @Param("status") ScanStatus status,
                                      Pageable pageable);

    /**
     * TTL-чистка эфемерных FREE_MARKETING-сканов (§4.6): по {@code kind}, а НЕ по {@code userId IS NULL}.
     * После split FREE/PREMIUM (Этап 5.5) free-скан может иметь {@code userId} (залогиненный юзер
     * запустил лид-магнит) — старый гард по владельцу его бы не удалил, и он жил бы вечно. Cascade
     * удалит findings/emails.
     */
    @Modifying
    @Query("DELETE FROM ComplianceScan s WHERE s.kind = :kind AND s.createdAt < :cutoff")
    int deleteByKindOlderThan(@Param("kind") ScanKind kind, @Param("cutoff") Instant cutoff);

    /**
     * Best-effort апдейт прогресса (§5.3): прямой UPDATE по id, только если статус НЕ терминальный.
     * Намеренно НЕ через dirty-checking entity — иначе {@code OptimisticLockingFailureException}
     * прилетал бы на flush/commit (после выхода из сервиса через proxy) и валил бы пайплайн. Прямой
     * запрос не трогает {@code @Version} и не конфликтует с lifecycle-переходами; двигает
     * {@code updated_at}, чтобы reaper не считал живой скан зависшим. Возвращает число обновлённых
     * строк (0 = скан уже терминальный/удалён — не ошибка).
     */
    @Modifying
    @Query("""
            UPDATE ComplianceScan s
            SET s.progressPct = :pct, s.progressStep = :step, s.updatedAt = :now
            WHERE s.id = :scanId
              AND s.status NOT IN (io.okdocs.compliance.contracts.enums.ScanStatus.COMPLETED,
                                   io.okdocs.compliance.contracts.enums.ScanStatus.PARTIAL,
                                   io.okdocs.compliance.contracts.enums.ScanStatus.FAILED)
            """)
    int updateProgress(@Param("scanId") UUID scanId,
                       @Param("pct") int pct,
                       @Param("step") String step,
                       @Param("now") Instant now);
}
