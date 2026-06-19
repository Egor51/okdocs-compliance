package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.HttpResponseInfo;
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
import java.util.List;

/**
 * Ответ не отдаёт {@code X-Content-Type-Options: nosniff}: браузер может «угадывать» тип содержимого
 * (MIME-sniffing) и исполнить как скрипт то, что им не является — вектор XSS. Категория SECURITY,
 * основание — ст. 19 152-ФЗ + OWASP Secure Headers.
 */
public final class MissingXContentTypeOptionsRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "MISSING_X_CONTENT_TYPE_OPTIONS",
            ScanJurisdiction.RU,
            FindingSeverity.LOW,
            FindingCategory.SECURITY,
            "Не задан заголовок X-Content-Type-Options: nosniff",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Secure Headers Project",
            "X-Content-Type-Options: nosniff запрещает браузеру угадывать MIME-тип ответа. Без него "
                    + "загруженный пользователем или сторонний контент может быть интерпретирован как "
                    + "исполняемый скрипт, что повышает риск XSS и компрометации вводимых ПДн.",
            "Добавьте заголовок X-Content-Type-Options: nosniff ко всем ответам.",
            "X-Content-Type-Options: nosniff настроен",
            "На проверенных страницах присутствует заголовок X-Content-Type-Options: nosniff.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<RuleFact> facts = new ArrayList<>();
        for (HttpResponseInfo r : HttpHeaderSupport.analyzableResponses(RuleSupport.httpResponses(ctx))) {
            String value = HttpHeaderSupport.lower(r.header("x-content-type-options"));
            if (!value.contains("nosniff")) {
                facts.add(new RuleFact(
                        DEFINITION.code(),
                        "На странице " + HttpHeaderSupport.shortUrl(r.url())
                                + " отсутствует заголовок X-Content-Type-Options: nosniff.",
                        r.url(),
                        SourceType.HTTP_HEADER,
                        EvidenceType.STATIC_ANALYSIS,
                        0.95,
                        "missing-header=x-content-type-options",
                        VerificationStatus.DETECTED));
            }
        }
        return facts;
    }
}
