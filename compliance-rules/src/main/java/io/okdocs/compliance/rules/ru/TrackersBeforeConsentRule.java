package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.RenderMode;
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
 * <b>Честность вероятностного результата (PLAN.md §3.2):</b> на STATIC нельзя наблюдать «трекер
 * сработал ДО клика по согласию» — нет исполнения JS. Поэтому на STATIC факт вероятностный
 * (UNVERIFIED, 0.60). На DYNAMIC (порядок загрузки/баннер виден) — CONFIRMED, 0.95.
 */
public final class TrackersBeforeConsentRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "POSSIBLE_TRACKERS_BEFORE_CONSENT",
            ScanJurisdiction.RU,
            FindingSeverity.HIGH,
            FindingCategory.TRACKERS,
            "Сторонние трекеры загружаются без получения согласия на cookie",
            "150 000 – 300 000 ₽ для юрлиц; при повторном нарушении 300 000 – 500 000 ₽ для юрлиц",
            "ст. 13.11 ч. 1 и ч. 1.1 КоАП РФ, ст. 6, ст. 9 152-ФЗ",
            "На страницах обнаружены скрипты сторонних аналитических и маркетинговых сервисов, при "
                    + "этом отсутствует механизм получения согласия пользователя на использование cookie. "
                    + "Необязательные трекеры относятся к обработке ПДн и требуют явного согласия до начала "
                    + "обработки. На статическом анализе порядок загрузки скриптов относительно cookie-баннера "
                    + "не наблюдается — вывод вероятностный.",
            "1. Внедрите cookie-менеджер. 2. Разделите cookie на обязательные, аналитические и "
                    + "маркетинговые. 3. Блокируйте загрузку скриптов трекеров до получения согласия. "
                    + "4. Загружайте аналитику и пиксели только после активного подтверждения в баннере. "
                    + "5. Обеспечьте возможность отзыва согласия в любой момент.");

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
        // Есть баннер — не можем утверждать, что трекеры грузятся до согласия.
        if (RuPatterns.hasCookieBanner(ctx)) {
            return List.of();
        }

        boolean anyDynamic = RuleSupport.evidenceType(ctx) == EvidenceType.DYNAMIC_RENDER;
        List<RuleFact> facts = new ArrayList<>();
        for (PageAnalysisResult page : pages) {
            Set<String> found = new LinkedHashSet<>();
            matchTracker(page.externalScriptDomains(), found);
            if (found.isEmpty()) {
                continue;
            }
            boolean dynamic = page.renderMode() == RenderMode.DYNAMIC || anyDynamic;
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "Трекеры загружаются без cookie-баннера: " + String.join(", ", found)
                            + (dynamic ? "." : " (порядок загрузки не подтверждён на статическом анализе)."),
                    page.url(),
                    SourceType.HTML,
                    dynamic ? EvidenceType.DYNAMIC_RENDER : EvidenceType.STATIC_ANALYSIS,
                    dynamic ? 0.95 : 0.60,
                    String.join(",", found),
                    dynamic ? VerificationStatus.CONFIRMED : VerificationStatus.UNVERIFIED));
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
