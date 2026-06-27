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
 * Ответ не защищён от встраивания во фрейм: нет ни {@code X-Frame-Options}, ни CSP-директивы
 * {@code frame-ancestors}. Это вектор clickjacking — подмена интерфейса для кражи вводимых ПДн.
 * Защита засчитывается, если присутствует ЛИБО заголовок, ЛИБО frame-ancestors в CSP. Категория
 * SECURITY, основание — ст. 19 152-ФЗ + OWASP Secure Headers.
 */
public final class MissingFrameProtectionRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "MISSING_FRAME_PROTECTION",
            ScanJurisdiction.RU,
            FindingSeverity.MEDIUM,
            FindingCategory.SECURITY,
            "Нет защиты от встраивания во фрейм (clickjacking)",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Secure Headers Project",
            "Отсутствие X-Frame-Options или CSP frame-ancestors позволяет встроить страницу в чужой "
                    + "iframe и наложить поверх неё фальшивый интерфейс (clickjacking), вынуждая "
                    + "пользователя неосознанно вводить персональные данные или совершать действия.",
            "Задайте X-Frame-Options: DENY (или SAMEORIGIN) либо директиву frame-ancestors в "
                    + "Content-Security-Policy, разрешив встраивание только доверенным источникам.",
            "Защита от clickjacking настроена",
            "На проверенных страницах присутствует X-Frame-Options или CSP frame-ancestors.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<RuleFact> facts = new ArrayList<>();
        for (HttpResponseInfo r : HttpHeaderSupport.analyzableResponses(RuleSupport.httpResponses(ctx))) {
            boolean hasXfo = r.hasHeader("x-frame-options");
            boolean hasFrameAncestors = HttpHeaderSupport.lower(r.header("content-security-policy"))
                    .contains("frame-ancestors");
            if (!hasXfo && !hasFrameAncestors) {
                facts.add(new RuleFact(
                        DEFINITION.code(),
                        "На странице " + HttpHeaderSupport.shortUrl(r.url())
                                + " нет защиты от clickjacking (ни X-Frame-Options, ни CSP frame-ancestors).",
                        r.url(),
                        SourceType.HTTP_HEADER,
                        EvidenceType.STATIC_ANALYSIS,
                        0.95,
                        "missing-header=x-frame-options;frame-ancestors",
                        VerificationStatus.DETECTED));
            }
        }
        return facts;
    }
}
