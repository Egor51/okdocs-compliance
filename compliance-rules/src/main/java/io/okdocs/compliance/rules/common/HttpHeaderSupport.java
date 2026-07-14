package io.okdocs.compliance.rules.common;

import io.okdocs.compliance.contracts.crawler.HttpResponseInfo;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Утилиты security-header правил Этапа 1. Только то, что общее для всех 9 правил: отбор финальных
 * (не-redirect) 2xx-ответов с HTML, признак «чувствительной» страницы (формы входа/ЛК/оплаты —
 * где кэширование/отсутствие защит особенно критично). Сами проверки заголовков — в правилах.
 */
public final class HttpHeaderSupport {

    /**
     * Только user-specific маршруты, способные вернуть данные кабинета/заказа. Проверяем полный
     * сегмент path: слово personal в /blog/personal-data-forms не делает статью чувствительной.
     * Публичные entry-страницы login/register/reset сюда намеренно не входят.
     */
    private static final Pattern SENSITIVE_URL = Pattern.compile(
            "(^|/)(account|cabinet|dashboard|lk|profile|personal|checkout|payment|"
                    + "oplata|order|orders|admin|lichnyj-kabinet|lichnyij-kabinet)(/|$)",
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

    /** Чувствительный ли user-specific ответ по отдельным сегментам URL path (query не учитывается). */
    public static boolean isSensitive(String url) {
        if (url == null) {
            return false;
        }
        try {
            String path = URI.create(url).getPath();
            return path != null && SENSITIVE_URL.matcher(path).find();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
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
