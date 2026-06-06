package io.okdocs.compliance.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.contracts.enums.UserRole;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Выпуск и валидация JWT — guest и user токенов (§4.3).
 * <ul>
 *   <li>guest: {@code sub=guestId}, claim {@code kind=GUEST};</li>
 *   <li>user/admin: {@code sub=userId}, claim {@code kind=USER}, claim {@code role}.</li>
 * </ul>
 */
@Service
public class JwtService {

    static final String CLAIM_KIND = "kind";
    static final String CLAIM_ROLE = "role";
    static final String KIND_GUEST = "GUEST";
    static final String KIND_USER = "USER";

    private final SecretKey key;
    private final ComplianceApiProperties.Auth authProps;

    public JwtService(ComplianceApiProperties properties) {
        this.authProps = properties.auth();
        byte[] secretBytes = authProps.jwtSecret().getBytes(StandardCharsets.UTF_8);
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    public String issueGuestToken(UUID guestId) {
        return buildToken(guestId.toString(), KIND_GUEST, null, authProps.guestTokenTtl());
    }

    public String issueUserToken(Long userId, UserRole role) {
        return buildToken(userId.toString(), KIND_USER, role.name(), authProps.accessTokenTtl());
    }

    public long accessTokenTtlSeconds() {
        return authProps.accessTokenTtl().toSeconds();
    }

    public long guestTokenTtlSeconds() {
        return authProps.guestTokenTtl().toSeconds();
    }

    private String buildToken(String subject, String kind, String role, Duration ttl) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(subject)
                .claim(CLAIM_KIND, kind)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key);
        if (role != null) {
            builder.claim(CLAIM_ROLE, role);
        }
        return builder.compact();
    }

    /**
     * Парсит и валидирует токен в {@link CompliancePrincipal}.
     *
     * @throws JwtException если токен невалиден/просрочен/подделан
     */
    public CompliancePrincipal parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String kind = claims.get(CLAIM_KIND, String.class);
        if (KIND_GUEST.equals(kind)) {
            return CompliancePrincipal.guest(UUID.fromString(claims.getSubject()));
        }
        if (KIND_USER.equals(kind)) {
            UserRole role = UserRole.valueOf(claims.get(CLAIM_ROLE, String.class));
            return CompliancePrincipal.user(Long.valueOf(claims.getSubject()), role);
        }
        throw new JwtException("Неизвестный тип токена: " + kind);
    }
}
