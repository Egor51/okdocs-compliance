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
 * {@code Content-Security-Policy} присутствует, но фактически не защищает: содержит {@code unsafe-inline},
 * {@code unsafe-eval} или директиву скриптов с {@code *} (любой источник). Такая политика создаёт ложное
 * ощущение защиты, фактически не ограничивая внедрение чужих скриптов. Категория SECURITY, основание —
 * ст. 19 152-ФЗ + OWASP. Срабатывает только при наличии CSP (отсутствие — {@link MissingCspRule}).
 */
public final class WeakCspRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "WEAK_CSP",
            ScanJurisdiction.RU,
            FindingSeverity.LOW,
            FindingCategory.SECURITY,
            "Content-Security-Policy содержит небезопасные директивы",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Secure Headers Project",
            "Политика безопасности с 'unsafe-inline', 'unsafe-eval' или источником '*' для скриптов "
                    + "разрешает выполнение произвольного встроенного/внешнего кода, фактически сводя на нет "
                    + "защиту от XSS и перехвата вводимых персональных данных.",
            "Уберите 'unsafe-inline' и 'unsafe-eval' из script-src, перейдите на nonce/hash для "
                    + "встроенных скриптов и перечислите конкретные доверенные источники вместо '*'.",
            "Content-Security-Policy не содержит небезопасных директив",
            "В проверенной Content-Security-Policy не обнаружены unsafe-inline, unsafe-eval или "
                    + "wildcard-источник скриптов.");

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
            String csp = HttpHeaderSupport.lower(r.header("content-security-policy"));
            if (csp.isBlank()) {
                continue;
            }
            List<String> weaknesses = new ArrayList<>();
            if (csp.contains("unsafe-inline")) {
                weaknesses.add("unsafe-inline");
            }
            if (csp.contains("unsafe-eval")) {
                weaknesses.add("unsafe-eval");
            }
            if (scriptSrcAllowsWildcard(csp)) {
                weaknesses.add("script-src *");
            }
            if (weaknesses.isEmpty()) {
                continue;
            }
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "Content-Security-Policy на странице " + HttpHeaderSupport.shortUrl(r.url())
                            + " содержит небезопасные директивы: " + String.join(", ", weaknesses) + ".",
                    r.url(),
                    SourceType.HTTP_HEADER,
                    EvidenceType.STATIC_ANALYSIS,
                    0.90,
                    "weak-csp=" + String.join(";", weaknesses),
                    VerificationStatus.DETECTED));
        }
        return facts;
    }

    /** script-src (или default-src как fallback) разрешает источник '*'. */
    private static boolean scriptSrcAllowsWildcard(String csp) {
        for (String directive : csp.split(";")) {
            String d = directive.trim();
            if (d.startsWith("script-src") || d.startsWith("default-src")) {
                for (String token : d.split("\\s+")) {
                    if ("*".equals(token.trim())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
