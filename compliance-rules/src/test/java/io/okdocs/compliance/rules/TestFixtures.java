package io.okdocs.compliance.rules;

import io.okdocs.compliance.contracts.crawler.CrawlerDiagnostics;
import io.okdocs.compliance.contracts.crawler.FormInfo;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.RegistryStatus;
import io.okdocs.compliance.contracts.enums.RenderMode;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;

import java.util.List;

/** Минимальные билдеры для конструирования контекста в unit-тестах правил. */
public final class TestFixtures {

    private TestFixtures() {
    }

    // ── Контекст ─────────────────────────────────────────────────────────────────────────────

    /** Контекст с дефолтным enrichment: hostCountry=RU, registry=FOUND (нарушений по ним нет). */
    public static ScanAnalysisContext ctx(PageAnalysisResult... pages) {
        return ctx("RU", RegistryStatus.FOUND, pages);
    }

    public static ScanAnalysisContext ctx(String hostCountry, RegistryStatus registry,
                                          PageAnalysisResult... pages) {
        return new ScanAnalysisContext(
                ScanJurisdiction.RU,
                List.of(pages),
                hostCountry,
                List.of("1.2.3.4"),
                registry,
                new CrawlerDiagnostics(pages.length, pages.length, 0, false));
    }

    // ── Страницы ─────────────────────────────────────────────────────────────────────────────

    public static PageAnalysisResult page(String url, String text, boolean cookiePresent,
                                          List<FormInfo> forms, List<String> internalLinks,
                                          List<String> externalScripts, String html) {
        return new PageAnalysisResult(
                url, "title", text, html,
                externalScripts, List.of(), internalLinks,
                cookiePresent, forms, RenderMode.STATIC);
    }

    /** Простая HTML-страница с формами, без cookie-баннера, без трекеров и ссылок на политику. */
    public static PageAnalysisResult simplePage(String url, FormInfo... forms) {
        return page(url, "обычный текст страницы", false, List.of(forms),
                List.of(), List.of(), "<html></html>");
    }

    /** Страница с внешними скриптами. */
    public static PageAnalysisResult pageWithScripts(String url, List<String> externalScripts) {
        return page(url, "текст", false, List.of(), List.of(), externalScripts, "<html></html>");
    }

    // ── Формы ────────────────────────────────────────────────────────────────────────────────

    // FormInfo: action, method, inputNames, hasPasswordField, hasFileUpload, hasCheckbox,
    //           hasConsentText, hasPrivacyPolicyLink, hasDefaultCheckedConsent, hasPdField

    /** Форма, собирающая ПДн (hasPdField=true), без согласия. */
    public static FormInfo dataFormNoConsent(String action) {
        return new FormInfo(action, "POST", List.of("email"),
                false, false, false, false, false, false, true);
    }

    /** Форма с ПДн + текстом согласия (надлежащее согласие). */
    public static FormInfo dataFormWithConsent(String action) {
        return new FormInfo(action, "POST", List.of("email"),
                false, false, true, true, true, false, true);
    }

    /** Форма с ПДн и предотмеченным согласием. */
    public static FormInfo dataFormDefaultChecked(String action) {
        return new FormInfo(action, "POST", List.of("email"),
                false, false, true, true, true, true, true);
    }

//    /** Форма без ПДн-полей (hasPdField=false) — напр. поиск/csrf. */
    public static FormInfo emptyForm(String action) {
        return new FormInfo(action, "GET", List.of("q"),
                false, false, false, false, false, false, false);
    }
}
