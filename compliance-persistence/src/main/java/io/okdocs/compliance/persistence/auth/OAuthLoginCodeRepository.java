package io.okdocs.compliance.persistence.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface OAuthLoginCodeRepository extends JpaRepository<OAuthLoginCode, String> {

    Optional<OAuthLoginCode> findByCodeHash(String codeHash);

    /**
     * Атомарный single-use claim кода (F.8, race-safe): помечает consumed ТОЛЬКО если код ещё не
     * использован и не просрочен. Возвращает число затронутых строк — ровно 1 у победителя гонки,
     * 0 у всех остальных параллельных {@code exchange}. Так два параллельных запроса не выдадут две
     * пары токенов по одному коду.
     */
    @Modifying
    @Query("""
            UPDATE OAuthLoginCode c SET c.consumedAt = :now
            WHERE c.codeHash = :codeHash AND c.consumedAt IS NULL AND c.expiresAt > :now
            """)
    int claim(@Param("codeHash") String codeHash, @Param("now") Instant now);
}
