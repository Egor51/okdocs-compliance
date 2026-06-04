package io.okdocs.compliance.contracts.crawler;

import io.okdocs.compliance.contracts.enums.RenderMode;

import java.util.List;

/** Результат обработки одной страницы краулером. Вход для правил. */
public record PageAnalysisResult(
        String url,
        String title,
        String text,
        String html,
        List<String> externalScriptDomains,
        List<String> externalStyleDomains,
        List<String> internalLinks,
        boolean cookiePresent,
        List<FormInfo> forms,
        RenderMode renderMode
) {
}
