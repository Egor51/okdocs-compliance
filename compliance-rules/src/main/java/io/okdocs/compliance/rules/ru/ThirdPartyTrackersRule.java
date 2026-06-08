package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
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
 * Сторонние трекеры (включая российские) загружаются на сайте. Метаданные перенесены из MVP
 * (okdocks {@code THIRD_PARTY_TRACKERS}). Детекция: домены из {@link RuTrackerDomains#ALL} в
 * external script/style доменах. Если трекеры упомянуты в политике — DETECTED/0.70, иначе
 * UNVERIFIED/0.90.
 */
public final class ThirdPartyTrackersRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "THIRD_PARTY_TRACKERS",
            ScanJurisdiction.RU,
            FindingSeverity.MEDIUM,
            FindingCategory.TRACKERS,
            "Используются сторонние трекеры без раскрытия в политике",
            "150 000 – 300 000 ₽ для юрлиц; при повторном нарушении 300 000 – 500 000 ₽ для юрлиц",
            "ст. 13.11 ч. 1 и ч. 1.1 КоАП РФ, ст. 6, ст. 18.1 152-ФЗ",
            "Если сайт использует Яндекс.Метрику, Google Analytics, рекламные пиксели, виджеты "
                    + "соцсетей, карты, онлайн-чаты или иные сторонние сервисы, такая обработка должна быть "
                    + "раскрыта в политике обработки ПДн с указанием категорий данных, целей и правового "
                    + "основания обработки.",
            "1. Проведите инвентаризацию всех сторонних скриптов и трекеров. 2. Перечислите их в "
                    + "политике обработки ПДн. 3. Укажите цели обработки и категории данных. 4. Для "
                    + "необязательной аналитики получайте согласие до загрузки трекеров. 5. Проверьте, "
                    + "не создают ли сервисы трансграничную передачу ПДн.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<PageAnalysisResult> pages = ctx.pages() == null ? List.of() : ctx.pages();
        List<RuleFact> facts = new ArrayList<>();

        for (PageAnalysisResult p : pages) {
            Set<String> found = new LinkedHashSet<>();
            matchAll(p.externalScriptDomains(), found);
            matchAll(p.externalStyleDomains(), found);
            if (found.isEmpty()) {
                continue;
            }
            // Считаем локально по доменам именно этой страницы (P2): иначе одно упоминание в
            // политике пометило бы DETECTED последующие, не упомянутые домены.
            boolean mentioned = RuPatterns.trackersMentionedInPolicy(ctx, found);
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "Загружаются сторонние трекеры: " + String.join(", ", found)
                            + (mentioned ? ". Упомянуты в политике — проверьте полноту раскрытия." : ""),
                    p.url(),
                    SourceType.HTML,
                    RuleSupport.evidenceType(ctx),
                    mentioned ? 0.70 : 0.90,
                    String.join(",", found),
                    mentioned ? VerificationStatus.DETECTED : VerificationStatus.UNVERIFIED));
        }
        return facts;
    }

    private static void matchAll(List<String> domains, Set<String> found) {
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
