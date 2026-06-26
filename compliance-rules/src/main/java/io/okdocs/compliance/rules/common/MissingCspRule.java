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
 * Ответ не отдаёт {@code Content-Security-Policy}: нет защиты от внедрения сторонних скриптов (XSS),
 * способных похитить вводимые ПДн. Категория SECURITY, основание — ст. 19 152-ФЗ + OWASP Secure
 * Headers. Если CSP присутствует, но слабый — это отдельное правило {@link WeakCspRule}.
 */
public final class MissingCspRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "MISSING_CSP",
            ScanJurisdiction.RU,
            FindingSeverity.MEDIUM,
            FindingCategory.SECURITY,
            "Не задан заголовок Content-Security-Policy",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Secure Headers Project",
            "Content-Security-Policy ограничивает источники, из которых страница загружает скрипты и "
                    + "ресурсы, снижая риск XSS — внедрения чужого кода, способного перехватить вводимые "
                    + "пользователем персональные данные. Отсутствие CSP оставляет страницу без этой защиты.",
            "Задайте Content-Security-Policy, ограничив источники скриптов/стилей доверенными доменами. "
                    + "Начните с режима Content-Security-Policy-Report-Only для оценки влияния, затем "
                    + "переведите в enforcing-режим.",
            "Content-Security-Policy настроена",
            "На проверенных страницах присутствует заголовок Content-Security-Policy.");

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
            if (!r.hasHeader("content-security-policy")) {
                facts.add(new RuleFact(
                        DEFINITION.code(),
                        "На странице " + HttpHeaderSupport.shortUrl(r.url())
                                + " отсутствует заголовок Content-Security-Policy.",
                        r.url(),
                        SourceType.HTTP_HEADER,
                        EvidenceType.STATIC_ANALYSIS,
                        0.95,
                        "missing-header=content-security-policy",
                        VerificationStatus.DETECTED));
            }
        }
        return facts;
    }
}
