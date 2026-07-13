package io.okdocs.compliance.rules.ru;

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
import io.okdocs.compliance.rules.RuleSupport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Сторонние трекеры присутствуют, но cookie-баннера нет — возможно, трекеры грузятся до согласия.
 * Метаданные перенесены из MVP (okdocks {@code TRACKERS_BEFORE_CONSENT}); код приведён к PLAN.md
 * §3.2 ({@code POSSIBLE_TRACKERS_BEFORE_CONSENT}).
 * <p>
 * <b>Честность результата:</b> {@code DETECTED} (0.95) выдаётся
 * только когда DYNAMIC-таймлайн через CDP реально зафиксировал запрос трекера ДО появления
 * cookie-баннера (поле {@code preConsentTrackerHosts}). Если порядок загрузки относительно согласия
 * не наблюдался — на STATIC (нет исполнения JS) или на DYNAMIC без зафиксированного pre-consent
 * запроса — факт остаётся вероятностным ({@code UNVERIFIED}, 0.60): трекер присутствует, но что он
 * сработал именно до согласия, не подтверждено.
 */
public final class TrackersBeforeConsentRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "POSSIBLE_TRACKERS_BEFORE_CONSENT",
            ScanJurisdiction.RU,
            FindingSeverity.HIGH,
            FindingCategory.TRACKERS,
            "Сторонний аналитический или маркетинговый сервис активен до выбора пользователя",
            null,
            "ст. 6, ст. 9 (если используется согласие), ст. 15 и ст. 18.1 152-ФЗ",
            "Динамический проход зафиксировал запрос известного стороннего сервиса до выбора "
                    + "пользователя. Это подтверждённый технический факт, но окончательная юридическая "
                    + "оценка зависит от состава данных, цели и основания обработки. На статическом "
                    + "анализе порядок запросов не наблюдается, поэтому результат остаётся непроверенным.",
            "1. Определите назначение и основание сервиса. 2. Если используется согласие, не "
                    + "инициализируйте сервис до выбора. 3. Убедитесь, что отказ реально блокирует запросы. "
                    + "4. Раскройте сервис, данные, цель и получателя в политике.",
            "Запросы известных трекеров до выбора не обнаружены",
            "В доступном динамическом таймлайне не выявлены запросы известных трекеров до выбора пользователя.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<PageAnalysisResult> pages = ctx.pages() == null ? List.of() : ctx.pages();
        if (pages.isEmpty()) {
            return List.of();
        }
        List<RuleFact> facts = new ArrayList<>();
        for (PageAnalysisResult page : pages) {
            Set<String> found = new LinkedHashSet<>();
            matchTracker(page.externalScriptDomains(), found);
            if (found.isEmpty()) {
                continue;
            }
            // Трекеры, чьи запросы РЕАЛЬНО наблюдались до согласия (DYNAMIC-таймлайн через CDP):
            // пересекаем наблюдённые pre-consent хосты со справочником тем же matchTracker.
            Set<String> preConsent = new LinkedHashSet<>();
            matchTracker(page.preConsentTrackerHosts(), preConsent);
            boolean confirmed = !preConsent.isEmpty();

            // DETECTED 0.95 — реально наблюдённый технический факт. Для RU он не равен автоматически
            // подтверждённому правонарушению: основание и состав данных требуют контекста.
            // отсутствии баннера). Иначе вероятностный UNVERIFIED 0.60: трекер есть, но порядок
            // загрузки относительно согласия не подтверждён (весь STATIC + DYNAMIC без pre-consent).
            Set<String> evidence = confirmed ? preConsent : found;
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "Наблюдалась загрузка известного стороннего сервиса до выбора пользователя: "
                            + String.join(", ", evidence)
                            + (confirmed
                                ? " (запрос наблюдался до появления cookie-баннера)."
                                : " (порядок загрузки относительно согласия не подтверждён)."),
                    page.url(),
                    SourceType.HTML,
                    confirmed ? EvidenceType.DYNAMIC_RENDER : EvidenceType.STATIC_ANALYSIS,
                    confirmed ? 0.95 : 0.60,
                    String.join(",", evidence),
                    confirmed ? VerificationStatus.DETECTED : VerificationStatus.UNVERIFIED));
        }
        return facts;
    }

    private static void matchTracker(List<String> domains, Set<String> found) {
        if (domains == null) {
            return;
        }
        for (String domain : domains) {
            for (String tracker : RuTrackerDomains.ALL) {
                if (RuleSupport.domainMatches(domain, tracker)) {
                    found.add(tracker);
                }
            }
        }
    }
}
