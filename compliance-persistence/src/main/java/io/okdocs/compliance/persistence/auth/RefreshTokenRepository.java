package io.okdocs.compliance.persistence.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHashAndRevokedFalse(String tokenHash);

    /** Включая отозванные — для reuse-detection ротации (повтор отозванного = сигнал кражи). */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUserIdAndRevokedFalse(Long userId);
}
