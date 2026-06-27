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

/**
 * HTTPS-ответ не отдаёт {@code Strict-Transport-Security}: браузер не принуждается к HTTPS, что
 * оставляет окно для downgrade/MITM при передаче ПДн. Категория SECURITY, основание — ст. 19 152-ФЗ
 * (меры защиты ПДн при передаче) + OWASP Secure Headers. Проверяется только на HTTPS-ответах:
 * на HTTP HSTS игнорируется браузером и отдельно покрыт HTTPS_NOT_ENFORCED (Этап 2).
 */
public final class MissingHstsRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "MISSING_HSTS",
            ScanJurisdiction.RU,
            FindingSeverity.MEDIUM,
            FindingCategory.SECURITY,
            "Не задан заголовок HSTS (Strict-Transport-Security)",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Secure Headers Project",
            "Заголовок Strict-Transport-Security предписывает браузеру обращаться к сайту только по "
                    + "HTTPS, защищая передаваемые персональные данные от перехвата при downgrade-атаках. "
                    + "Его отсутствие на HTTPS-ответах ослабляет защиту канала передачи ПДн.",
            "Включите Strict-Transport-Security на HTTPS-ответах, например: "
                    + "max-age=31536000; includeSubDomains. Перед includeSubDomains убедитесь, что все "
                    + "поддомены доступны по HTTPS.",
            "HSTS настроен",
            "На проверенных HTTPS-страницах присутствует заголовок Strict-Transport-Security.");

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
        for (HttpResponseInfo r : HttpHeaderSupport.analyzableResponses(RuleSupport.httpResponses(ctx))) {
            if (r.url() == null || !r.url().toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
                continue;
            }
            if (!r.hasHeader("strict-transport-security")) {
                facts.add(new RuleFact(
                        DEFINITION.code(),
                        "На странице " + HttpHeaderSupport.shortUrl(r.url())
                                + " отсутствует заголовок Strict-Transport-Security.",
                        r.url(),
                        SourceType.HTTP_HEADER,
                        EvidenceType.STATIC_ANALYSIS,
                        0.95,
                        "missing-header=strict-transport-security",
                        VerificationStatus.DETECTED,
                        "MISSING_HSTS",
                        java.util.Map.of("page", HttpHeaderSupport.shortUrl(r.url()))));
            }
        }
        return facts;
    }
}
