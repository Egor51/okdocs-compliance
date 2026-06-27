package io.okdocs.compliance.contracts.auth;

import io.okdocs.compliance.contracts.enums.OAuthProvider;

/**
 * Нормализованный профиль из OAuth-провайдера (F.2 §F9) — то, что бэкенд извлекает из ответа
 * провайдера независимо от его формата. На основании этого создаётся/линкуется {@link OAuthUserInfo}
 * к локальному аккаунту.
 *
 * @param provider       какой провайдер
 * @param providerUserId стабильный id юзера у провайдера (sub/uid) — ключ связки
 * @param email          email из профиля (может быть {@code null}, если провайдер не отдал)
 * @param emailVerified  подтверждён ли email провайдером — ТОЛЬКО при {@code true} допустима
 *                       auto-link к существующему аккаунту по email (иначе account takeover)
 * @param name           отображаемое имя (может быть {@code null})
 */
public record OAuthUserInfo(
        OAuthProvider provider,
        String providerUserId,
        String email,
        boolean emailVerified,
        String name
) {
}
