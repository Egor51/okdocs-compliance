package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.FormInfo;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.FormPurpose;
import io.okdocs.compliance.contracts.enums.RenderMode;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleDefinition;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.RuleSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * Форма собирает персональные данные, но без механизма согласия на обработку. Метаданные и
 * пороги confidence перенесены из MVP (okdocks {@code UNPROTECTED_DATA_FORMS}). Детекция:
 * {@link RuleSupport#collectsData} + отсутствие согласия ({@link RuPatterns#pageHasConsent}).
 * confidence зависит от режима: STATIC = 0.80, DYNAMIC = 0.95.
 */
public final class UnprotectedDataFormsRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "UNPROTECTED_DATA_FORMS",
            ScanJurisdiction.RU,
            FindingSeverity.CRITICAL,
            FindingCategory.FORMS,
            "Формы сбора данных без надлежащего согласия пользователя",
            "300 000 – 700 000 ₽ для юрлиц; при повторном нарушении 1 000 000 – 1 500 000 ₽ для "
                    + "юрлиц, 500 000 – 1 000 000 ₽ для ИП",
            "ст. 13.11 ч. 2 и ч. 2.1 КоАП РФ, ст. 9 152-ФЗ",
            "Если форма собирает персональные данные и для такой обработки требуется согласие, оно "
                    + "должно быть конкретным, информированным, сознательным, предметным и однозначным. "
                    + "С 1 сентября 2025 года согласие должно оформляться отдельно от иной информации. "
                    + "Отсутствие отдельного согласия рядом с формой создаёт высокий риск признания "
                    + "обработки неправомерной.",
            "1. Добавьте к каждой форме отдельный чекбокс согласия на обработку ПДн. 2. Чекбокс не "
                    + "должен быть отмечен по умолчанию. 3. Разместите рядом ссылку на отдельный текст "
                    + "согласия. 4. Блокируйте отправку формы до активного действия пользователя. "
                    + "5. Логируйте факт получения согласия: дату, время, версию текста, IP и источник формы.",
            "Формы без видимого механизма согласия не обнаружены в проверяемом scope",
            "Среди обследованных контактных, lead-, subscription- и нераспознанных форм с ПДн-полями "
                    + "сканер не обнаружил форму без видимого механизма согласия. Auth/order/search-формы "
                    + "этим правилом не оцениваются.");

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
            boolean pageHasPdForm = page.forms().stream().anyMatch(UnprotectedDataFormsRule::isConsentRelevant);
            if (!pageHasPdForm || RuPatterns.pageHasConsent(page)) {
                continue;
            }
            boolean dynamic = page.renderMode() == RenderMode.DYNAMIC;
            String formsDesc = describeForms(page);
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "Найдена форма сбора персональных данных без согласия. Формы: " + formsDesc + ".",
                    page.url(),
                    SourceType.HTML,
                    dynamic ? EvidenceType.DYNAMIC_RENDER : EvidenceType.STATIC_ANALYSIS,
                    dynamic ? 0.95 : 0.80,
                    "pd-form-detected" + (dynamic ? ",dynamic-render" : ""),
                    VerificationStatus.UNVERIFIED));
        }
        return facts;
    }

    private static String describeForms(PageAnalysisResult page) {
        List<String> names = new ArrayList<>();
        for (FormInfo form : page.forms()) {
            if (isConsentRelevant(form)) {
                String action = form.action();
                String target = action == null || action.isBlank() ? "JavaScript handler" : action;
                names.add("purpose=" + form.purpose() + ", target=" + target);
            }
        }
        return names.isEmpty() ? "не удалось определить" : String.join("; ", names);
    }

    private static boolean isConsentRelevant(FormInfo form) {
        if (!RuleSupport.collectsData(form)) {
            return false;
        }
        FormPurpose purpose = form.purpose();
        return purpose == FormPurpose.CONTACT
                || purpose == FormPurpose.LEAD
                || purpose == FormPurpose.SUBSCRIPTION
                || purpose == FormPurpose.FILE_UPLOAD
                || purpose == FormPurpose.UNKNOWN;
    }
}
