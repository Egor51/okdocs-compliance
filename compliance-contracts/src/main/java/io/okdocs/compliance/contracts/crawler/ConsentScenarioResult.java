package io.okdocs.compliance.contracts.crawler;

import java.util.List;

/**
 * Результат прогона consent-сценариев на одной странице во время DYNAMIC-рендера (§ PLAN-jurisdictions
 * Фаза 4). Краулер после снимка «до согласия» взаимодействует с баннером (Reject, затем Accept) и
 * фиксирует состояние cookies/трекеров после каждого действия. Вход для EU/UK consent-правил:
 * «трекеры остались после Reject», «нет кнопки Reject», «предотмеченные тогглы».
 * <p>
 * Все поля nullable/пусты, если сценарий не отрабатывался (нет баннера, STATIC-скан, CDP-сбой):
 * {@code available == false} означает «не проверяли» — правило тогда НЕ должно давать «нарушений нет».
 * Снимки «после Accept» опциональны (Accept может не понадобиться правилам, дороже по времени).
 */
public record ConsentScenarioResult(
        ConsentBannerInfo banner,
        List<ObservedCookie> afterRejectCookies,
        List<String> afterRejectTrackerHosts,
        List<String> afterRejectStorageKeys,
        List<ObservedCookie> afterAcceptCookies,
        List<NetworkRequestObservation> afterRejectRequests,
        boolean inspectionCompleted,
        boolean rejectFound,
        boolean rejectClicked,
        boolean postRejectSnapshotAvailable,
        ConsentScenarioFailureReason failureReason,
        boolean requestTimelineTruncated,
        boolean available
) {
    public ConsentScenarioResult {
        banner = banner == null ? ConsentBannerInfo.notFound() : banner;
        afterRejectCookies = afterRejectCookies == null ? List.of() : List.copyOf(afterRejectCookies);
        afterRejectTrackerHosts = afterRejectTrackerHosts == null
                ? List.of() : List.copyOf(afterRejectTrackerHosts);
        afterRejectStorageKeys = afterRejectStorageKeys == null
                ? List.of() : List.copyOf(afterRejectStorageKeys);
        afterAcceptCookies = afterAcceptCookies == null ? List.of() : List.copyOf(afterAcceptCookies);
        afterRejectRequests = afterRejectRequests == null ? List.of() : List.copyOf(afterRejectRequests);
        failureReason = failureReason == null ? ConsentScenarioFailureReason.CDP_ERROR : failureReason;
    }

    /**
     * Совместимый конструктор для ранее сохранённых результатов и существующих правил/тестов.
     * Старое {@code available=true} трактуется как успешно выполненный Reject-сценарий.
     */
    public ConsentScenarioResult(
            ConsentBannerInfo banner,
            List<ObservedCookie> afterRejectCookies,
            List<String> afterRejectTrackerHosts,
            List<ObservedCookie> afterAcceptCookies,
            boolean available
    ) {
        this(banner, afterRejectCookies, afterRejectTrackerHosts, List.of(), afterAcceptCookies,
                List.of(), available, banner != null && banner.rejectButtonFound(),
                available && banner != null && banner.rejectButtonFound(), available,
                available ? ConsentScenarioFailureReason.NONE : ConsentScenarioFailureReason.CDP_ERROR,
                false, available);
    }

    public static ConsentScenarioResult failed(
            ConsentBannerInfo banner,
            boolean inspectionCompleted,
            boolean rejectFound,
            boolean rejectClicked,
            ConsentScenarioFailureReason reason
    ) {
        return new ConsentScenarioResult(banner, List.of(), List.of(), List.of(), List.of(), List.of(),
                inspectionCompleted, rejectFound, rejectClicked, false, reason, false, false);
    }

    /** Сценарий не отрабатывался (STATIC / CDP-сбой) — {@code available == false}. */
    public static ConsentScenarioResult notEvaluated() {
        return failed(ConsentBannerInfo.notFound(), false, false, false,
                ConsentScenarioFailureReason.CDP_ERROR);
    }

    public static ConsentScenarioResult disabled() {
        return failed(ConsentBannerInfo.notFound(), false, false, false,
                ConsentScenarioFailureReason.SCENARIO_DISABLED);
    }
}
