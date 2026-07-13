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
import io.okdocs.compliance.rules.common.ConsentSupport;
import io.okdocs.compliance.rules.common.TrackerCatalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RU-specific consistency check: the site offered and executed a Reject action, but known analytics
 * or marketing hosts were still observed afterwards. Unlike the EU rule, it does not require an
 * equal-level Reject button and does not infer a standalone cookie-law violation. It records an
 * observable mismatch between the user's choice and the site's behaviour; the legal basis remains
 * contextual under 152-ФЗ.
 */
public final class ConsentChoiceNotEffectiveRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "RU_CONSENT_CHOICE_NOT_EFFECTIVE",
            ScanJurisdiction.RU,
            FindingSeverity.HIGH,
            FindingCategory.CONSENT,
            "Отказ пользователя не прекращает стороннюю аналитику",
            null,
            "ст. 5, ст. 6, ст. 9 (если используется согласие), ст. 15 и ст. 18.1 152-ФЗ",
            "Динамический сценарий выполнил доступное действие отказа, но после него продолжились "
                    + "запросы к известным аналитическим или маркетинговым сервисам. Это подтверждённое "
                    + "несоответствие поведения сайта заявленному выбору; окончательная квалификация "
                    + "зависит от цели и правового основания обработки.",
            "1. Передавайте сигнал отказа во все tag manager и SDK. 2. После отказа блокируйте "
                    + "необязательные запросы и очищайте созданные ими идентификаторы. 3. Повторите "
                    + "проверку в чистом профиле. 4. Документируйте основание для сервисов, которые "
                    + "должны работать независимо от выбора.",
            "После отказа продолжение известных трекеров не обнаружено",
            "В выполненных consent-сценариях после отказа не наблюдались запросы известных трекеров.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public boolean appliesTo(ScanAnalysisContext ctx) {
        return ConsentSupport.scenarioAvailable(ctx);
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<RuleFact> facts = new ArrayList<>();
        for (PageAnalysisResult page : ConsentSupport.pagesWithScenario(ctx)) {
            if (page.consentScenario().banner() == null
                    || !page.consentScenario().banner().rejectButtonFound()) {
                continue; // RU rule does not treat absence/placement of Reject as a standalone violation.
            }
            Set<String> providers = new LinkedHashSet<>();
            for (String host : page.consentScenario().afterRejectTrackerHosts()) {
                TrackerCatalog.lookup(host).ifPresent(info -> providers.add(info.provider()));
            }
            if (providers.isEmpty()) {
                continue;
            }
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "После выполненного отказа продолжились запросы известных сервисов: "
                            + String.join(", ", providers) + ".",
                    page.url(),
                    SourceType.HTML,
                    EvidenceType.DYNAMIC_RENDER,
                    0.95,
                    "reject-executed;trackers-after-reject;" + String.join(",", providers),
                    VerificationStatus.DETECTED,
                    "TRACKERS_AFTER_REJECT",
                    java.util.Map.of("items", providers)));
        }
        return facts;
    }
}
