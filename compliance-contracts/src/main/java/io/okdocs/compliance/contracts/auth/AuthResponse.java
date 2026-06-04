package io.okdocs.compliance.contracts.auth;

/** Ответ на login/register/refresh: пара токенов + профиль. */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserProfileDto user
) {
}
