package io.okdocs.compliance.rules.common;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;

import java.util.List;

/**
 * Утилиты cookie-правил Этапа 4: определяют, наблюдались ли вообще cookies/storage в скане. Нужны
 * для {@code Rule.appliesTo(ctx)} — если данных нет (DYNAMIC не запускался или деградировал),
 * cookie-правило НЕ должно давать PASSED («нарушений нет»), а помечается NOT_EVALUATED («не проверяли»).
 */
public final class CookieSupport {

    private CookieSupport() {
    }

    private static List<PageAnalysisResult> pages(ScanAnalysisContext ctx) {
        return ctx.pages() == null ? List.of() : ctx.pages();
    }

    /** Доступен ли хотя бы один CDP-снимок cookies: пустой список при этом является валидным PASS-входом. */
    static boolean cookiesSnapshotAvailable(ScanAnalysisContext ctx) {
        return pages(ctx).stream()
                .anyMatch(PageAnalysisResult::preConsentCookiesSnapshotAvailable);
    }

    /** Доступен ли хотя бы один JS-снимок Web Storage: пустой список при этом является валидным PASS-входом. */
    static boolean storageSnapshotAvailable(ScanAnalysisContext ctx) {
        return pages(ctx).stream()
                .anyMatch(PageAnalysisResult::preConsentStorageSnapshotAvailable);
    }
}
