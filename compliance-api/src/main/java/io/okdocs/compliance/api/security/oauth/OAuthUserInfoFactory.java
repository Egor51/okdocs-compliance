package io.okdocs.compliance.api.security.oauth;

import io.okdocs.compliance.contracts.auth.OAuthUserInfo;
import io.okdocs.compliance.contracts.enums.OAuthProvider;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;

import java.util.Locale;
import java.util.Map;

/**
 * Маппинг сырых атрибутов профиля провайдера → нормализованный {@link OAuthUserInfo} (F.8).
 * <p>
 * Ключевое — корректный {@code emailVerified} на каждого провайдера: от него зависит безопасная
 * auto-link в {@code OAuthAccountService} (F.9). Если провайдер не гарантирует подтверждённость
 * email — ставим {@code false} (лучше завести отдельный аккаунт, чем рискнуть takeover).
 */
public final class OAuthUserInfoFactory {

    private OAuthUserInfoFactory() {
    }

    /**
     * @param registrationId id регистрации Spring (ключ из {@code spring.security.oauth2.client...})
     * @param attributes     атрибуты профиля от провайдера
     */
    public static OAuthUserInfo from(String registrationId, Map<String, Object> attributes) {
        OAuthProvider provider = parseProvider(registrationId);
        return switch (provider) {
            case GOOGLE -> google(attributes);
            case GITHUB -> github(attributes);
            case YANDEX -> yandex(attributes);
            case VK -> vk(attributes);
        };
    }

    private static OAuthProvider parseProvider(String registrationId) {
        try {
            return OAuthProvider.valueOf(registrationId.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ComplianceValidationException("Неизвестный OAuth-провайдер: " + registrationId);
        }
    }

    /** Google OIDC: sub, email, email_verified (bool), name. */
    private static OAuthUserInfo google(Map<String, Object> a) {
        return new OAuthUserInfo(
                OAuthProvider.GOOGLE,
                str(a.get("sub")),
                str(a.get("email")),
                bool(a.get("email_verified")),
                str(a.get("name")));
    }

    /**
     * GitHub: id (number), email (может быть null при приватном профиле), name/login. Базовый профиль
     * НЕ несёт флага подтверждённости → emailVerified=false (безопасный дефолт; для true нужен
     * отдельный вызов /user/emails, вне MVP-каркаса).
     */
    private static OAuthUserInfo github(Map<String, Object> a) {
        String name = a.get("name") != null ? str(a.get("name")) : str(a.get("login"));
        return new OAuthUserInfo(
                OAuthProvider.GITHUB,
                str(a.get("id")),
                str(a.get("email")),
                false,
                name);
    }

    /**
     * Яндекс: id, default_email, real_name/display_name. Email привязки Яндекс-аккаунта считаются
     * подтверждёнными аккаунтом → emailVerified=true при наличии email.
     */
    private static OAuthUserInfo yandex(Map<String, Object> a) {
        String email = a.get("default_email") != null ? str(a.get("default_email")) : firstEmail(a.get("emails"));
        String name = a.get("real_name") != null ? str(a.get("real_name")) : str(a.get("display_name"));
        return new OAuthUserInfo(
                OAuthProvider.YANDEX,
                str(a.get("id")),
                email,
                email != null,
                name);
    }

    /**
     * VK: id, email приходит не из userinfo, а из token response (часто отсутствует) и НЕ гарантирован
     * подтверждённым → emailVerified=false. Имя — first_name + last_name.
     */
    private static OAuthUserInfo vk(Map<String, Object> a) {
        String name = join(str(a.get("first_name")), str(a.get("last_name")));
        return new OAuthUserInfo(
                OAuthProvider.VK,
                str(a.get("id")),
                str(a.get("email")),
                false,
                name);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static boolean bool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }

    private static String firstEmail(Object emails) {
        if (emails instanceof java.util.List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0));
        }
        return null;
    }

    private static String join(String a, String b) {
        String s = ((a == null ? "" : a) + " " + (b == null ? "" : b)).trim();
        return s.isEmpty() ? null : s;
    }
}
