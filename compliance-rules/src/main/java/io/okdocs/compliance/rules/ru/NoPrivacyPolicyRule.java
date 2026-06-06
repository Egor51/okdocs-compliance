package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleDefinition;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.RuleSupport;

import java.util.List;

/**
 * На сайте не найдена ссылка/упоминание политики обработки персональных данных. Метаданные и
 * пороги перенесены из MVP (okdocks {@code RuleCatalog.NO_PRIVACY_POLICY}). Детекция — regex по
 * ссылкам и тексту страниц ({@link RuPatterns#hasPolicyLink}).
 */
public final class NoPrivacyPolicyRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "NO_PRIVACY_POLICY",
            FindingSeverity.HIGH,
            FindingCategory.DOCUMENTS,
            "На сайте не найдена политика обработки персональных данных",
            "10 000 – 20 000 ₽ для ИП, 30 000 – 60 000 ₽ для юрлиц",
            "ст. 13.11 ч. 3 КоАП РФ, ч. 2 ст. 18.1 152-ФЗ",
            "Оператор персональных данных обязан опубликовать документ, определяющий его политику "
                    + "в отношении обработки персональных данных, или обеспечить неограниченный доступ "
                    + "к такому документу. Если на сайте есть формы для сбора имени, телефона, email или "
                    + "иных данных пользователя, отсутствие доступной политики создаёт высокий риск нарушения.",
            "1. Подготовьте политику обработки персональных данных. 2. Разместите её на отдельной "
                    + "странице по постоянному URL. 3. Добавьте ссылку на политику в подвал сайта и рядом "
                    + "с формами сбора данных. 4. Убедитесь, что страница доступна без авторизации.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<PageAnalysisResult> pages = ctx.pages() == null ? List.of() : ctx.pages();
        if (pages.isEmpty() || RuPatterns.hasPolicyLink(ctx)) {
            return List.of();
        }
        return List.of(new RuleFact(
                DEFINITION.code(),
                "Не найдена ссылка на политику обработки персональных данных ни в подвале, "
                        + "ни в навигации сайта.",
                pages.get(0).url(),
                SourceType.HTML,
                RuleSupport.evidenceType(ctx),
                null,
                "privacy-policy-link-absent",
                VerificationStatus.UNVERIFIED));
    }
}
