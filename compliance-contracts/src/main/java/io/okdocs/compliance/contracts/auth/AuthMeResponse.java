package io.okdocs.compliance.contracts.auth;

import io.okdocs.compliance.contracts.enums.PrincipalType;

import java.util.UUID;

/**
 * Ответ на {@code GET /api/auth/me}.
 * <ul>
 *   <li>guest token → {@code principalType=GUEST}, {@code guestId} заполнен;</li>
 *   <li>user token → {@code principalType=USER}, {@code user} заполнен;</li>
 *   <li>нет токена → {@code authenticated=false}.</li>
 * </ul>
 */
public record AuthMeResponse(
        boolean authenticated,
        PrincipalType principalType,
        UserProfileDto user,
        UUID guestId
) {
}
