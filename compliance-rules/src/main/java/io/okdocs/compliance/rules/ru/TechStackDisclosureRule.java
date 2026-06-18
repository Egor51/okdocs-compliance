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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ответ раскрывает версию технологического стека через {@code Server}, {@code X-Powered-By},
 * {@code X-AspNet-Version} и подобные заголовки с номером версии. Это упрощает подбор известных
 * уязвимостей под конкретную версию ПО, обрабатывающего ПДн. Срабатывает только при наличии ВЕРСИИ
 * (просто "nginx" без версии — не находка). Категория SECURITY, основание — ст. 19 152-ФЗ + OWASP.
 */
public final class TechStackDisclosureRule implements Rule {

    /** Заголовки, раскрывающие используемое ПО/версию. */
    private static final List<String> DISCLOSURE_HEADERS = List.of(
            "server", "x-powered-by", "x-aspnet-version", "x-aspnetmvc-version",
            "x-generator", "x-drupal-cache", "x-runtime");

    /** Признак версии в значении: цифра с точкой или слешем (nginx/1.18.0, PHP/8.1). */
    private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+|/\\d+)");

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "TECH_STACK_DISCLOSURE",
            ScanJurisdiction.RU,
            FindingSeverity.LOW,
            FindingCategory.SECURITY,
            "Раскрытие версии технологического стека в заголовках",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Secure Headers Project",
            "Заголовки Server, X-Powered-By и подобные с номером версии раскрывают конкретные версии "
                    + "веб-сервера и платформы. Это упрощает атакующему подбор известных уязвимостей под "
                    + "точную версию ПО, обрабатывающего персональные данные.",
            "Скройте версии в заголовках: server_tokens off (nginx), ServerTokens Prod (Apache), "
                    + "уберите X-Powered-By и X-AspNet*-Version на уровне приложения/прокси.",
            "Версии технологического стека не раскрываются",
            "На проверенных страницах заголовки не раскрывают версии веб-сервера и платформы.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<RuleFact> facts = new ArrayList<>();
        for (HttpResponseInfo r : HttpHeaderSupport.analyzableResponses(RuleSupport.httpResponses(ctx))) {
            Set<String> disclosed = new LinkedHashSet<>();
            for (String name : DISCLOSURE_HEADERS) {
                String value = r.header(name);
                if (value == null || value.isBlank()) {
                    continue;
                }
                // x-powered-by/x-aspnet-version раскрывают платформу даже без номера версии.
                boolean alwaysDisclosing = !name.equals("server");
                if (alwaysDisclosing || VERSION.matcher(value).find()) {
                    disclosed.add(name + ": " + value.trim());
                }
            }
            if (disclosed.isEmpty()) {
                continue;
            }
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "На странице " + HttpHeaderSupport.shortUrl(r.url())
                            + " раскрывается технологический стек: " + String.join("; ", disclosed) + ".",
                    r.url(),
                    SourceType.HTTP_HEADER,
                    EvidenceType.STATIC_ANALYSIS,
                    0.85,
                    "tech-stack-disclosure;" + String.join(";", disclosed),
                    VerificationStatus.DETECTED));
        }
        return facts;
    }
}
