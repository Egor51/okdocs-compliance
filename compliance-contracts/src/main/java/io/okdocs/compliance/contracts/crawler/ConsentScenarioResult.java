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
        List<ObservedCookie> afterAcceptCookies,
        boolean available
) {
    public ConsentScenarioResult {
        afterRejectCookies = afterRejectCookies == null ? List.of() : List.copyOf(afterRejectCookies);
        afterRejectTrackerHosts = afterRejectTrackerHosts == null
                ? List.of() : List.copyOf(afterRejectTrackerHosts);
        afterAcceptCookies = afterAcceptCookies == null ? List.of() : List.copyOf(afterAcceptCookies);
    }

    /** Сценарий не отрабатывался (нет баннера / STATIC / сбой) — {@code available == false}. */
    public static ConsentScenarioResult notEvaluated() {
        return new ConsentScenarioResult(ConsentBannerInfo.notFound(), List.of(), List.of(),
                List.of(), false);
    }
}
