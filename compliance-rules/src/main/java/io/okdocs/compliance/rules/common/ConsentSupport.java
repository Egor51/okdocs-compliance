package io.okdocs.compliance.rules.common;

import io.okdocs.compliance.contracts.crawler.ConsentScenarioResult;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Утилиты consent-правил (Фаза 5): определяют, отрабатывался ли вообще consent-сценарий
 * (Reject/Accept-проход краулера, Фаза 4), и дают доступ к страницам с валидным сценарием.
 * Jurisdiction-neutral — переиспользуется EU/UK consent-правилами.
 * <p>
 * Нужны для {@code Rule.appliesTo(ctx)}: если сценарий не отрабатывался ({@code consentScenario==null}
 * или {@code available==false} — STATIC-скан, consent-scenarios выключены, CDP-сбой), consent-правило
 * НЕ должно давать PASSED («нарушений нет»), а помечается NOT_EVALUATED («не проверяли»).
 */
public final class ConsentSupport {

    private ConsentSupport() {
    }

    private static List<PageAnalysisResult> pages(ScanAnalysisContext ctx) {
        return ctx.pages() == null ? List.of() : ctx.pages();
    }

    /** Валиден ли consent-сценарий страницы (отработал и доступен). */
    public static boolean hasScenario(PageAnalysisResult page) {
        ConsentScenarioResult s = page.consentScenario();
        return s != null && s.available();
    }

    /** Отработал ли consent-сценарий хотя бы на одной странице скана. */
    public static boolean scenarioAvailable(ScanAnalysisContext ctx) {
        return pages(ctx).stream().anyMatch(ConsentSupport::hasScenario);
    }

    /** Страницы с валидным consent-сценарием (порядок обхода сохранён). */
    public static List<PageAnalysisResult> pagesWithScenario(ScanAnalysisContext ctx) {
        List<PageAnalysisResult> result = new ArrayList<>();
        for (PageAnalysisResult p : pages(ctx)) {
            if (hasScenario(p)) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Готовый suffix " CMP: {provider}." для structured-evidence param (или пусто). Один формат для
     * всех consent-правил (EU/UK/FR/DE/ES), чтобы не дублировать тернарник в каждом детекторе.
     */
    public static String cmpSuffix(io.okdocs.compliance.contracts.crawler.ConsentBannerInfo banner) {
        if (banner == null || banner.cmpProvider() == null || banner.cmpProvider().isBlank()) {
            return "";
        }
        return " CMP: " + banner.cmpProvider() + ".";
    }
}
