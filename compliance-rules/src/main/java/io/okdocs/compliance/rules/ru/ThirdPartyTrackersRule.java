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
 * external script/style доменах. Finding создаётся только для сервиса, который не удалось найти в
 * тексте политики: сам сетевой сервис наблюдаем, а отсутствие раскрытия проверяется в доступном
 * документе. Полнота и юридическое качество раскрытия остаются отдельной задачей.
 */
public final class ThirdPartyTrackersRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "THIRD_PARTY_TRACKERS",
            ScanJurisdiction.RU,
            FindingSeverity.MEDIUM,
            FindingCategory.TRACKERS,
            "Сторонний аналитический сервис не раскрыт в доступной политике",
            null,
            "ст. 6 и ст. 18.1 152-ФЗ",
            "Если сайт использует Яндекс.Метрику, Google Analytics, рекламные пиксели, виджеты "
                    + "соцсетей, карты, онлайн-чаты или иные сторонние сервисы, такая обработка должна быть "
                    + "раскрыта в политике обработки ПДн с указанием категорий данных, целей и правового "
                    + "основания обработки.",
            "1. Проведите инвентаризацию всех сторонних скриптов и трекеров. 2. Перечислите их в "
                    + "политике обработки ПДн. 3. Укажите цели обработки и категории данных. 4. Для "
                    + "аналитики определите применимое правовое основание. 5. Проверьте, "
                    + "не создают ли сервисы трансграничную передачу ПДн.",
            "Нераскрытые сторонние трекеры не обнаружены",
            "Сканер не выявил сторонние трекеры, не раскрытые в политике обработки ПДн.");

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
            // Фильтруем каждый домен отдельно: раскрытие Yandex не должно скрыть нераскрытый
            // HubSpot на той же странице.
            found.removeIf(domain -> RuPatterns.trackersMentionedInPolicy(ctx, Set.of(domain)));
            if (found.isEmpty()) {
                continue;
            }
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "Наблюдается сторонний сервис, не найденный в доступном тексте политики: "
                            + String.join(", ", found) + ".",
                    p.url(),
                    SourceType.HTML,
                    RuleSupport.evidenceType(ctx),
                    0.85,
                    String.join(",", found),
                    VerificationStatus.DETECTED));
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
