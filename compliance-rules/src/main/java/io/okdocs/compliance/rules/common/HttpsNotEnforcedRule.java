package io.okdocs.compliance.rules.common;

import io.okdocs.compliance.contracts.crawler.HttpResponseInfo;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.JurisdictionLayer;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleDefinition;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.RuleSupport;

import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import java.util.Locale;

/**
 * HTTP не принуждается к HTTPS: ответ по {@code http://} либо отдаёт контент (2xx), либо редиректит
 * снова на {@code http://}, вместо немедленного редиректа на {@code https://}. Передача ПДн идёт по
 * незашифрованному каналу. Работает на redirect-цепочке из technical-паспорта (Этап 1), TLS-сокет не
 * нужен. Категория SECURITY, основание — ст. 19 152-ФЗ + OWASP.
 */
public final class HttpsNotEnforcedRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "HTTPS_NOT_ENFORCED",
            ScanJurisdiction.RU,
            FindingSeverity.HIGH,
            FindingCategory.SECURITY,
            "HTTP не перенаправляется на HTTPS",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Transport Layer Security",
            "Если страница доступна по http:// и не выполняет немедленный редирект на https://, "
                    + "персональные данные пользователя могут передаваться по незашифрованному каналу и быть "
                    + "перехвачены на любом промежуточном узле сети.",
            "Настройте постоянный редирект (301) с http:// на https:// для всех страниц и включите HSTS.",
            "HTTP перенаправляется на HTTPS",
            "Проверенные http-ответы выполняют редирект на https.");

    /**
     * Reusable technical-правило: детектор jurisdiction-neutral, поэтому работает в слоях RU/EU/UK.
     * Per-layer legal-метаданные (RU: 152-ФЗ; EU: GDPR; UK: UK GDPR/PECR) резолвятся отдельно по
     * (code, layer); {@code definition()} даёт RU-метаданные own-слоя.
     */
    @Override
    public Set<JurisdictionLayer> supportedLayers() {
        return Set.of(JurisdictionLayer.RU, JurisdictionLayer.EU, JurisdictionLayer.UK);
    }

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<RuleFact> facts = new ArrayList<>();
        for (HttpResponseInfo r : RuleSupport.httpResponses(ctx)) {
            if (!isHttp(r.url())) {
                continue;
            }
            // http-ответ безопасен ТОЛЬКО если это редирект на https. Иначе (2xx по http, либо
            // редирект на http) — HTTPS не принуждается.
            boolean redirectsToHttps = r.redirect()
                    && r.redirectLocation() != null
                    && resolvedTargetIsHttps(r.url(), r.redirectLocation());
            if (!redirectsToHttps) {
                facts.add(new RuleFact(
                        DEFINITION.code(),
                        "Ответ по " + HttpHeaderSupport.shortUrl(r.url())
                                + (r.redirect() ? " перенаправляет снова на http, а не на https."
                                        : " отдаётся по незащищённому http без редиректа на https."),
                        r.url(),
                        SourceType.HTTP_HEADER,
                        EvidenceType.STATIC_ANALYSIS,
                        0.95,
                        "https-not-enforced;status=" + r.statusCode(),
                        VerificationStatus.DETECTED));
            }
        }
        return facts;
    }

    private static boolean isHttp(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("http://");
    }

    /** Резолвит Location относительно базового URL и проверяет, что результат — https. */
    private static boolean resolvedTargetIsHttps(String base, String location) {
        String loc = location.trim().toLowerCase(Locale.ROOT);
        if (loc.startsWith("https://")) {
            return true;
        }
        if (loc.startsWith("http://")) {
            return false;
        }
        // Относительный Location (//host, /path) наследует схему базового http → не https.
        return false;
    }
}
