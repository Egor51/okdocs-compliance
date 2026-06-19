package io.okdocs.compliance.contracts.crawler;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Снимок одного HTTP-ответа (один redirect-хоп или финальная страница), собранный static-краулером
 * из {@code PinnedHttpFetcher.Response}. Site-level факт в {@link TechnicalAnalysisResult}, а не поле
 * страницы: один URL может дать цепочку ответов (http→https→финал), и правилам security-заголовков
 * нужен каждый ответ со своим {@code url}, чтобы сказать «на /login нет CSP», «http не редиректит на https».
 * <p>
 * {@code headers} — имена в нижнем регистре (как их складывает {@code PinnedHttpFetcher}). {@code redirect}
 * = был ли это 3xx-хоп; {@code redirectLocation} — куда (только для redirect-хопов, иначе null).
 */
public record HttpResponseInfo(
        String url,
        int statusCode,
        Map<String, List<String>> headers,
        boolean redirect,
        String redirectLocation
) {
    public HttpResponseInfo {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** Первое значение заголовка (case-insensitive) или null. Удобный аксессор для правил. */
    public String header(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** Есть ли заголовок (хотя бы одно непустое значение). */
    public boolean hasHeader(String name) {
        String value = header(name);
        return value != null && !value.isBlank();
    }
}
