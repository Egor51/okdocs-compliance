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
 * {@code Access-Control-Allow-Origin: *} — открытый CORS: любой сторонний сайт может читать ответы
 * от имени пользователя. Особенно опасно вместе с {@code Access-Control-Allow-Credentials: true}
 * (доступ к данным аутентифицированной сессии). Категория SECURITY, основание — ст. 19 152-ФЗ + OWASP.
 */
public final class WildcardCorsRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "WILDCARD_CORS",
            ScanJurisdiction.RU,
            FindingSeverity.MEDIUM,
            FindingCategory.SECURITY,
            "Открытая политика CORS (Access-Control-Allow-Origin: *)",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Secure Headers Project",
            "Заголовок Access-Control-Allow-Origin: * разрешает любому стороннему домену выполнять "
                    + "межсайтовые запросы к ресурсу и читать ответ. Если ответ содержит персональные данные, "
                    + "это открывает их чтение произвольным сайтам; в сочетании с allow-credentials риск "
                    + "распространяется на данные аутентифицированной сессии.",
            "Замените '*' на явный список доверенных origin'ов. Никогда не используйте '*' вместе с "
                    + "Access-Control-Allow-Credentials: true.",
            "Открытая политика CORS не обнаружена",
            "На проверенных страницах не найден Access-Control-Allow-Origin: *.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<RuleFact> facts = new ArrayList<>();
        for (HttpResponseInfo r : HttpHeaderSupport.analyzableResponses(RuleSupport.httpResponses(ctx))) {
            String origin = HttpHeaderSupport.lower(r.header("access-control-allow-origin")).trim();
            if (!"*".equals(origin)) {
                continue;
            }
            boolean withCredentials = HttpHeaderSupport.lower(r.header("access-control-allow-credentials"))
                    .contains("true");
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "На странице " + HttpHeaderSupport.shortUrl(r.url())
                            + " задан Access-Control-Allow-Origin: *"
                            + (withCredentials ? " вместе с Access-Control-Allow-Credentials: true "
                                    + "(критичная конфигурация)." : "."),
                    r.url(),
                    SourceType.HTTP_HEADER,
                    EvidenceType.STATIC_ANALYSIS,
                    withCredentials ? 0.95 : 0.90,
                    "wildcard-cors" + (withCredentials ? ";with-credentials" : ""),
                    VerificationStatus.DETECTED));
        }
        return facts;
    }
}
