package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.FormInfo;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Чекбокс согласия отмечен по умолчанию ({@code checked}). Метаданные перенесены из MVP
 * (okdocks {@code CONSENT_DEFAULT_CHECKED}). В okdocks признак вычислялся Jsoup'ом по атрибуту
 * {@code checked}; у нас этот признак выкладывает краулер в {@link FormInfo#hasDefaultCheckedConsent}
 * (виден в HTML, детектируется на STATIC). confidence: STATIC = 0.90, DYNAMIC = 0.95.
 */
public final class ConsentDefaultCheckedRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "CONSENT_DEFAULT_CHECKED",
            ScanJurisdiction.RU,
            FindingSeverity.HIGH,
            FindingCategory.CONSENT,
            "Согласие на обработку ПДн отмечено по умолчанию",
            "300 000 – 700 000 ₽ для юрлиц; при повторном нарушении 1 000 000 – 1 500 000 ₽ для юрлиц",
            "ст. 13.11 ч. 2 и ч. 2.1 КоАП РФ, ст. 9 152-ФЗ",
            "Согласие должно быть конкретным, информированным, сознательным и оформляться отдельно "
                    + "от иных документов или информации. Предзаполненный чекбокс создаёт высокий риск "
                    + "признания согласия ненадлежащим, поскольку пользователь не совершает активного "
                    + "действия по его предоставлению.",
            "1. Уберите атрибут checked у чекбоксов согласия. 2. Сделайте отправку формы невозможной "
                    + "до активной отметки чекбокса. 3. Разместите рядом ссылку на текст согласия. "
                    + "4. Не объединяйте согласие на обработку ПДн с офертой, рассылкой или маркетингом.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<PageAnalysisResult> pages = ctx.pages() == null ? List.of() : ctx.pages();
        List<RuleFact> facts = new ArrayList<>();
        for (PageAnalysisResult page : pages) {
            if (page.forms() == null) {
                continue;
            }
            boolean defaultChecked = page.forms().stream().anyMatch(FormInfo::hasDefaultCheckedConsent);
            if (!defaultChecked) {
                continue;
            }
            boolean dynamic = page.renderMode() == RenderMode.DYNAMIC;
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "Чекбокс согласия отмечен по умолчанию (атрибут checked).",
                    page.url(),
                    SourceType.HTML,
                    dynamic ? EvidenceType.DYNAMIC_RENDER : EvidenceType.STATIC_ANALYSIS,
                    dynamic ? 0.95 : 0.90,
                    "checkbox-checked-by-default" + (dynamic ? ",dynamic-render" : ""),
                    VerificationStatus.CONFIRMED));
        }
        return facts;
    }
}
