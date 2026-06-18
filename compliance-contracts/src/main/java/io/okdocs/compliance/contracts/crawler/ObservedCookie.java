package io.okdocs.compliance.contracts.crawler;

/**
 * Cookie, наблюдённая в браузере во время DYNAMIC-рендера через CDP. Атрибуты (secure/httpOnly/
 * sameSite) недоступны на STATIC (Jsoup не исполняет JS и не видит Set-Cookie с флагами) и из
 * {@code document.cookie} — снимаются только CDP {@code Network.getCookies}.
 * <p>
 * {@code session} — cookie без срока истечения (нет expires/max-age): живёт до закрытия вкладки,
 * типична для идентификаторов сессии. Вход для cookie-правил Этапа 4 (Phase 1): отсутствие Secure/
 * HttpOnly, трекинг до согласия. Поле {@code scenario} (before/reject/accept) — задел под Phase 2,
 * на Phase 1 не заполняется.
 */
public record ObservedCookie(
        String name,
        String domain,
        boolean secure,
        boolean httpOnly,
        String sameSite,
        boolean session
) {
}
