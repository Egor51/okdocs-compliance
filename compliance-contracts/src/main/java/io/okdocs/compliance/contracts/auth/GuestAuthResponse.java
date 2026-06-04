package io.okdocs.compliance.contracts.auth;

import java.util.UUID;

/**
 * Ответ на {@code POST /api/auth/guest}: первый заход, выдаётся guest JWT.
 * Фронтенд переиспользует {@code accessToken} для всех последующих обращений.
 */
public record GuestAuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UUID guestId
) {
}
