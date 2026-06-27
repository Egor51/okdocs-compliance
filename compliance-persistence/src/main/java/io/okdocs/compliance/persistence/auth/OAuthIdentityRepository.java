package io.okdocs.compliance.persistence.auth;

import io.okdocs.compliance.contracts.enums.OAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OAuthIdentityRepository extends JpaRepository<OAuthIdentity, Long> {

    /** Найти связку по внешней личности — повторный вход тем же соц-аккаунтом. */
    Optional<OAuthIdentity> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);
}
