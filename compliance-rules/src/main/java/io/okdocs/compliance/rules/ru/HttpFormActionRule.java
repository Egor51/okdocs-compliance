package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.rules.common.HttpHeaderSupport;

import io.okdocs.compliance.contracts.crawler.FormInfo;
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
import java.util.List;
import java.util.Locale;

/**
 * Форма, собирающая ПДн, отправляет данные на незащищённый {@code http://}-адрес (атрибут
 * {@code action}). Даже если сама страница по HTTPS, submit уходит открытым текстом. Работает на
 * {@link FormInfo#action()} из краула (Этап 1), TLS-сокет не нужен. Категория SECURITY, основание —
 * ст. 19 152-ФЗ + OWASP. Дополняет {@code UnprotectedDataFormsRule} (та смотрит схему страницы),
 * фокусируясь именно на cross-scheme submit: HTTPS-страница → HTTP-action.
 */
public final class HttpFormActionRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "HTTP_FORM_ACTION",
            ScanJurisdiction.RU,
            FindingSeverity.HIGH,
            FindingCategory.SECURITY,
            "Форма с персональными данными отправляется по незащищённому HTTP",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Transport Layer Security",
            "Атрибут action формы указывает на http://-адрес: введённые персональные данные при "
                    + "отправке передаются по незашифрованному каналу независимо от того, по какому протоколу "
                    + "загружена сама страница.",
            "Измените action формы на https://-адрес и обеспечьте передачу всех данных форм только "
                    + "по защищённому соединению.",
            "Формы отправляются по защищённому соединению",
            "Сканер не нашёл форм с персональными данными, отправляемых на http://-адрес.");

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
            for (FormInfo form : page.forms()) {
                if (!RuleSupport.collectsData(form)) {
                    continue;
                }
                if (isHttpAction(form.action())) {
                    facts.add(new RuleFact(
                            DEFINITION.code(),
                            "На странице " + HttpHeaderSupport.shortUrl(page.url())
                                    + " форма с персональными данными отправляется на незащищённый адрес: "
                                    + form.action(),
                            page.url(),
                            SourceType.HTML,
                            RuleSupport.evidenceType(ctx),
                            0.95,
                            "http-form-action;action=" + form.action(),
                            VerificationStatus.DETECTED));
                }
            }
        }
        return facts;
    }

    private static boolean isHttpAction(String action) {
        return action != null && action.trim().toLowerCase(Locale.ROOT).startsWith("http://");
    }
}
