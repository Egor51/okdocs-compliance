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
 * Загрузка скриптов/стилей с иностранных доменов-получателей ПДн (риск трансграничной передачи,
 * ст. 12 152-ФЗ). Метаданные перенесены из MVP (okdocks {@code CROSS_BORDER_TRANSFER}); код
 * приведён к PLAN.md §3.2 ({@code POSSIBLE_CROSS_BORDER_TRANSFER}, category HOSTING). Российские
 * сервисы исключены — они в {@link ThirdPartyTrackersRule}. В политике → DETECTED/0.70, иначе
 * UNVERIFIED/0.85.
 */
public final class CrossBorderTransferRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "POSSIBLE_CROSS_BORDER_TRANSFER",
            ScanJurisdiction.RU,
            FindingSeverity.HIGH,
            FindingCategory.HOSTING,
            "Обнаружен риск трансграничной передачи персональных данных",
            "Размер санкции зависит от установленного состава правонарушения и фактических "
                    + "обстоятельств; требуется отдельная юридическая квалификация",
            "ст. 12 152-ФЗ, ст. 22 152-ФЗ, ст. 13.11 КоАП РФ",
            "Использование зарубежных сервисов может создавать трансграничную передачу персональных "
                    + "данных, если оператор фактически передаёт им данные пользователей. До начала такой "
                    + "передачи оператор обязан направить в Роскомнадзор отдельное уведомление. Сам факт "
                    + "наличия внешнего ресурса не всегда подтверждает передачу ПДн — требуется техническая "
                    + "и юридическая проверка фактических потоков данных.",
            "1. Проверьте, какие категории данных фактически передаются зарубежным сервисам. "
                    + "2. Определите государства и получателей данных. 3. Проверьте наличие уведомления о "
                    + "трансграничной передаче. 4. При необходимости замените зарубежные сервисы на "
                    + "российские аналоги. 5. Отразите трансграничную передачу в политике и согласиях.",
            "Иностранные скрипты и стили на обследованных страницах не обнаружены",
            "Среди загруженных скриптов и стилей сканер не нашёл домены из используемого "
                    + "каталога иностранных сервисов. OAuth, серверные интеграции и действия после "
                    + "авторизации этим правилом не проверяются.");

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
            matchForeign(p.externalScriptDomains(), found);
            matchForeign(p.externalStyleDomains(), found);
            if (found.isEmpty()) {
                continue;
            }
            // Локально по доменам этой страницы (P2), не «липкий» флаг на весь проход.
            boolean mentioned = RuPatterns.trackersMentionedInPolicy(ctx, found);
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "Загружаются скрипты иностранных сервисов (трансграничная передача ПДн): "
                            + String.join(", ", found)
                            + (mentioned ? ". Упомянуты в политике — проверьте наличие уведомления РКН." : ""),
                    p.url(),
                    SourceType.HTML,
                    RuleSupport.evidenceType(ctx),
                    mentioned ? 0.70 : 0.85,
                    String.join(",", found),
                    mentioned ? VerificationStatus.DETECTED : VerificationStatus.UNVERIFIED));
        }
        return facts;
    }

    private static void matchForeign(List<String> domains, Set<String> found) {
        if (domains == null) {
            return;
        }
        for (String domain : domains) {
            for (String service : RuTrackerDomains.FOREIGN) {
                if (RuleSupport.domainMatches(domain, service)) {
                    found.add(service);
                }
            }
        }
    }
}
