package io.okdocs.compliance.rules.common;

import io.okdocs.compliance.contracts.crawler.HttpResponseInfo;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Утилиты security-header правил Этапа 1. Только то, что общее для всех 9 правил: отбор финальных
 * (не-redirect) 2xx-ответов с HTML, признак «чувствительной» страницы (формы входа/ЛК/оплаты —
 * где кэширование/отсутствие защит особенно критично). Сами проверки заголовков — в правилах.
 */
public final class HttpHeaderSupport {

    /** Страницы, на которых отсутствие защит/кэширование ответа особенно опасно для ПДн. */
    private static final Pattern SENSITIVE_URL = Pattern.compile(
            "(login|signin|sign-in|auth|account|cabinet|lk|profile|personal|checkout|payment|"
                    + "oplata|order|register|signup|sign-up|password|reset|admin|"
                    + "lichnyj-kabinet|lichnyij-kabinet|vhod|regist)",
            Pattern.CASE_INSENSITIVE);

    private HttpHeaderSupport() {
    }

    /**
     * Финальные ответы для анализа заголовков: не redirect-хопы и статус 2xx. Redirect-хопы
     * исключаем — у 3xx нет тела/смысла security-заголовков; их анализирует HTTPS-enforcement правило
     * (Этап 2). 4xx/5xx тоже отбрасываем: заголовки страницы ошибки не репрезентативны.
     */
    public static List<HttpResponseInfo> analyzableResponses(List<HttpResponseInfo> responses) {
        return responses.stream()
                .filter(r -> !r.redirect())
                .filter(r -> r.statusCode() >= 200 && r.statusCode() < 300)
                .toList();
    }

    /** Чувствительная ли это страница по URL (вход/ЛК/оплата/регистрация). */
    public static boolean isSensitive(String url) {
        return url != null && SENSITIVE_URL.matcher(url).find();
    }

    /** Короткий хвост URL для evidence: путь без query (или сам URL, если не парсится). */
    public static String shortUrl(String url) {
        if (url == null) {
            return "";
        }
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }

    /** Значение заголовка в нижнем регистре или пустая строка (для подстрочного поиска). */
    public static String lower(String headerValue) {
        return headerValue == null ? "" : headerValue.toLowerCase(Locale.ROOT);
    }
}
