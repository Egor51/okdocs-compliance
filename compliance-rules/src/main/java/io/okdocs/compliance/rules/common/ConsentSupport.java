package io.okdocs.compliance.rules.common;

import io.okdocs.compliance.contracts.crawler.ConsentScenarioResult;
import io.okdocs.compliance.contracts.crawler.ConsentScenarioFailureReason;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.rules.RuleApplicability;

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

    public static boolean hasBannerInspection(PageAnalysisResult page) {
        ConsentScenarioResult s = page.consentScenario();
        return s != null && s.inspectionCompleted();
    }

    public static boolean hasPostRejectSnapshot(PageAnalysisResult page) {
        ConsentScenarioResult s = page.consentScenario();
        return s != null && s.rejectClicked() && s.postRejectSnapshotAvailable();
    }

    /** Отработал ли consent-сценарий хотя бы на одной странице скана. */
    public static boolean scenarioAvailable(ScanAnalysisContext ctx) {
        return pages(ctx).stream().anyMatch(ConsentSupport::hasScenario);
    }

    public static boolean bannerInspectionAvailable(ScanAnalysisContext ctx) {
        return pages(ctx).stream().anyMatch(ConsentSupport::hasBannerInspection);
    }

    public static boolean postRejectSnapshotAvailable(ScanAnalysisContext ctx) {
        return pages(ctx).stream().anyMatch(ConsentSupport::hasPostRejectSnapshot);
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

    public static List<PageAnalysisResult> pagesWithBannerInspection(ScanAnalysisContext ctx) {
        return pages(ctx).stream().filter(ConsentSupport::hasBannerInspection).toList();
    }

    public static List<PageAnalysisResult> pagesWithPostRejectSnapshot(ScanAnalysisContext ctx) {
        return pages(ctx).stream().filter(ConsentSupport::hasPostRejectSnapshot).toList();
    }

    /** Причина, почему правило, требующее выполненный Reject, нельзя оценить. */
    public static RuleApplicability postRejectApplicability(ScanAnalysisContext ctx) {
        if (postRejectSnapshotAvailable(ctx)) {
            return RuleApplicability.available();
        }
        ConsentScenarioFailureReason reason = pages(ctx).stream()
                .map(PageAnalysisResult::consentScenario)
                .filter(java.util.Objects::nonNull)
                .map(ConsentScenarioResult::failureReason)
                .filter(java.util.Objects::nonNull)
                .filter(r -> r != ConsentScenarioFailureReason.NONE)
                .findFirst()
                .orElse(ConsentScenarioFailureReason.CDP_ERROR);
        String detail = switch (reason) {
            case SCENARIO_DISABLED -> "Consent-сценарий отключён конфигурацией.";
            case BANNER_NOT_FOUND -> "Cookie-баннер не найден на проверенных динамических страницах.";
            case REJECT_NOT_FOUND -> "Cookie-баннер найден, но действие отказа не найдено.";
            case REJECT_CLICK_FAILED -> "Действие отказа найдено, но браузер не смог его выполнить.";
            case POST_REJECT_CAPTURE_FAILED -> "Отказ выполнен, но состояние после отказа снять не удалось.";
            case TIMEOUT -> "Consent-сценарий не завершился за отведённое время.";
            case CDP_ERROR, NONE -> "Consent-сценарий не выполнен из-за ошибки браузера.";
        };
        return RuleApplicability.unavailable(detail, "NOT_EVALUATED_CONSENT_" + reason.name());
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
