package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.ObservedCookie;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleDefinition;
import io.okdocs.compliance.rules.RuleFact;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Трекинговые cookie (Яндекс.Метрика, Google Analytics, Meta Pixel и т.п.) установлены ДО получения
 * согласия. Дополняет {@code TrackersBeforeConsentRule} (тот — про сетевые запросы трекеров): здесь
 * доказательство именно в cookie, выставленных до взаимодействия с баннером (DYNAMIC через CDP).
 * Текущий проход не кликает по баннеру, поэтому наблюдённые cookie — это состояние «до согласия».
 * Категория COOKIES, основание — ст. 6, ст. 9 152-ФЗ (согласие до обработки) + ст. 13.11 КоАП РФ.
 */
public final class TrackingCookiesBeforeConsentRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "TRACKING_COOKIES_BEFORE_CONSENT",
            ScanJurisdiction.RU,
            FindingSeverity.HIGH,
            FindingCategory.COOKIES,
            "Трекинговые cookie устанавливаются до получения согласия",
            "150 000 – 300 000 ₽ для юрлиц; при повторном нарушении 300 000 – 500 000 ₽ для юрлиц",
            "ст. 13.11 ч. 1 и ч. 1.1 КоАП РФ, ст. 6, ст. 9 152-ФЗ",
            "В браузере до взаимодействия с cookie-баннером уже присутствуют cookie аналитических и "
                    + "маркетинговых сервисов. Установка необязательных трекинговых cookie до получения "
                    + "согласия пользователя нарушает требование о согласии до начала обработки ПДн.",
            "1. Блокируйте установку трекинговых cookie до получения согласия. 2. Загружайте скрипты "
                    + "аналитики и пикселей только после активного подтверждения в баннере. 3. Разделите "
                    + "cookie на обязательные и необязательные. 4. Обеспечьте отзыв согласия.",
            "Трекинговые cookie до согласия не обнаружены",
            "Среди наблюдённых до согласия cookie трекинговых не зафиксировано.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public boolean appliesTo(ScanAnalysisContext ctx) {
        return CookieSupport.cookiesSnapshotAvailable(ctx);
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<PageAnalysisResult> pages = ctx.pages() == null ? List.of() : ctx.pages();
        List<RuleFact> facts = new ArrayList<>();
        for (PageAnalysisResult page : pages) {
            if (page.preConsentCookies() == null || page.preConsentCookies().isEmpty()) {
                continue;
            }
            Set<String> trackers = new LinkedHashSet<>();
            for (ObservedCookie c : page.preConsentCookies()) {
                if (TrackerCookieNames.isTracker(c.name())) {
                    trackers.add(c.name());
                }
            }
            if (trackers.isEmpty()) {
                continue;
            }
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "На странице " + HttpHeaderSupport.shortUrl(page.url())
                            + " до согласия установлены трекинговые cookie: " + String.join(", ", trackers) + ".",
                    page.url(),
                    SourceType.HTML,
                    EvidenceType.DYNAMIC_RENDER,
                    0.90,
                    "tracking-cookies-before-consent;" + String.join(",", trackers),
                    VerificationStatus.CONFIRMED));
        }
        return facts;
    }
}
