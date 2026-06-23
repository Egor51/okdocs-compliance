package io.okdocs.compliance.api.security;

import io.jsonwebtoken.JwtException;
import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.contracts.enums.PrincipalType;
import io.okdocs.compliance.contracts.enums.UserRole;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService(props(
            "test-secret-test-secret-test-secret-test-secret-test-secret-1234"));

    @Test
    void guestTokenRoundtrip() {
        UUID guestId = UUID.randomUUID();
        String token = jwtService.issueGuestToken(guestId);

        CompliancePrincipal principal = jwtService.parse(token);

        assertThat(principal.type()).isEqualTo(PrincipalType.GUEST);
        assertThat(principal.guestId()).isEqualTo(guestId);
        assertThat(principal.userId()).isNull();
    }

    @Test
    void userTokenRoundtrip() {
        String token = jwtService.issueUserToken(42L, UserRole.USER);

        CompliancePrincipal principal = jwtService.parse(token);

        assertThat(principal.type()).isEqualTo(PrincipalType.USER);
        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.role()).isEqualTo(UserRole.USER);
        assertThat(principal.guestId()).isNull();
    }

    @Test
    void adminTokenMapsToAdminPrincipalType() {
        String token = jwtService.issueUserToken(7L, UserRole.ADMIN);
        assertThat(jwtService.parse(token).type()).isEqualTo(PrincipalType.ADMIN);
    }

    @Test
    void tamperedTokenRejected() {
        String token = jwtService.issueGuestToken(UUID.randomUUID());
        String tampered = token.substring(0, token.length() - 2) + "xy";
        assertThatThrownBy(() -> jwtService.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithDifferentSecretRejected() {
        String token = jwtService.issueGuestToken(UUID.randomUUID());
        JwtService other = new JwtService(props(
                "OTHER-secret-OTHER-secret-OTHER-secret-OTHER-secret-OTHER-9999"));
        assertThatThrownBy(() -> other.parse(token)).isInstanceOf(JwtException.class);
    }

    private static ComplianceApiProperties props(String secret) {
        var auth = new ComplianceApiProperties.Auth(secret, Duration.ofMinutes(30),
                Duration.ofDays(30), Duration.ofDays(7));
        return new ComplianceApiProperties(null, null, null, null, null, auth, null, null, null);
    }
}
