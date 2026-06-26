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
 * Чувствительная страница (вход/ЛК/оплата/регистрация) отдаётся без запрета кэширования: ответ с
 * ПДн может осесть в кэше браузера/прокси/CDN и стать доступен на общем устройстве или промежуточном
 * узле. Срабатывает только для sensitive-URL без {@code Cache-Control: no-store} (или no-cache/private).
 * Категория SECURITY, основание — ст. 19 152-ФЗ + OWASP.
 */
public final class SensitivePageCacheableRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "SENSITIVE_PAGE_CACHEABLE",
            ScanJurisdiction.RU,
            FindingSeverity.MEDIUM,
            FindingCategory.SECURITY,
            "Чувствительная страница кэшируется (нет Cache-Control: no-store)",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Secure Headers Project",
            "Страницы входа, личного кабинета, оплаты и регистрации содержат персональные данные. Без "
                    + "запрета кэширования (Cache-Control: no-store) такой ответ может сохраниться в кэше "
                    + "браузера, прокси или CDN и оказаться доступен другому пользователю общего устройства "
                    + "или на промежуточном узле.",
            "На страницах с ПДн установите Cache-Control: no-store (при необходимости также no-cache, "
                    + "private) и Pragma: no-cache.",
            "Кэширование чувствительных страниц запрещено",
            "На проверенных страницах входа/ЛК/оплаты установлен запрет кэширования (Cache-Control: "
                    + "no-store/no-cache/private).");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<RuleFact> facts = new ArrayList<>();
        for (HttpResponseInfo r : HttpHeaderSupport.analyzableResponses(RuleSupport.httpResponses(ctx))) {
            if (!HttpHeaderSupport.isSensitive(r.url())) {
                continue;
            }
            String cacheControl = HttpHeaderSupport.lower(r.header("cache-control"));
            boolean noStore = cacheControl.contains("no-store")
                    || cacheControl.contains("no-cache")
                    || cacheControl.contains("private");
            if (!noStore) {
                facts.add(new RuleFact(
                        DEFINITION.code(),
                        "Чувствительная страница " + HttpHeaderSupport.shortUrl(r.url())
                                + " отдаётся без запрета кэширования"
                                + (cacheControl.isBlank() ? " (Cache-Control отсутствует)."
                                        : " (Cache-Control: " + cacheControl + ")."),
                        r.url(),
                        SourceType.HTTP_HEADER,
                        EvidenceType.STATIC_ANALYSIS,
                        0.85,
                        "sensitive-page-cacheable;cache-control=" + (cacheControl.isBlank() ? "absent" : cacheControl),
                        VerificationStatus.DETECTED));
            }
        }
        return facts;
    }
}
