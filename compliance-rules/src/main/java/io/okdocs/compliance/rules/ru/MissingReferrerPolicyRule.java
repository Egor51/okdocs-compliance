package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.rules.common.HttpHeaderSupport;

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
 * Ответ не отдаёт {@code Referrer-Policy}: при переходах URL текущей страницы (потенциально с ПДн в
 * пути/параметрах) утекает сторонним ресурсам в заголовке Referer. Категория SECURITY, основание —
 * ст. 19 152-ФЗ + OWASP Secure Headers.
 */
public final class MissingReferrerPolicyRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "MISSING_REFERRER_POLICY",
            ScanJurisdiction.RU,
            FindingSeverity.LOW,
            FindingCategory.SECURITY,
            "Не задан заголовок Referrer-Policy",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Secure Headers Project",
            "Referrer-Policy управляет тем, какая часть URL передаётся в заголовке Referer при переходах "
                    + "и загрузке сторонних ресурсов. Без него полный URL (включая возможные идентификаторы "
                    + "и ПДн в параметрах) может утекать на внешние домены.",
            "Задайте Referrer-Policy, например strict-origin-when-cross-origin или no-referrer для "
                    + "страниц с чувствительными данными.",
            "Referrer-Policy настроен",
            "На проверенных страницах присутствует заголовок Referrer-Policy.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<RuleFact> facts = new ArrayList<>();
        for (HttpResponseInfo r : HttpHeaderSupport.analyzableResponses(RuleSupport.httpResponses(ctx))) {
            if (!r.hasHeader("referrer-policy")) {
                facts.add(new RuleFact(
                        DEFINITION.code(),
                        "На странице " + HttpHeaderSupport.shortUrl(r.url())
                                + " отсутствует заголовок Referrer-Policy.",
                        r.url(),
                        SourceType.HTTP_HEADER,
                        EvidenceType.STATIC_ANALYSIS,
                        0.95,
                        "missing-header=referrer-policy",
                        VerificationStatus.DETECTED));
            }
        }
        return facts;
    }
}
