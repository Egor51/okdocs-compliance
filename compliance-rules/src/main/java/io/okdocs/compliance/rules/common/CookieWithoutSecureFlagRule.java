package io.okdocs.compliance.rules.common;

import io.okdocs.compliance.contracts.crawler.ObservedCookie;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cookie выставлена без флага {@code Secure}: передаётся по незашифрованному HTTP и может быть
 * перехвачена. Наблюдается только на DYNAMIC через CDP ({@link ObservedCookie}) — атрибуты cookie
 * недоступны на STATIC. Категория COOKIES, основание — ст. 19 152-ФЗ (меры защиты ПДн) + OWASP.
 */
public final class CookieWithoutSecureFlagRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "COOKIE_WITHOUT_SECURE_FLAG",
            ScanJurisdiction.RU,
            FindingSeverity.MEDIUM,
            FindingCategory.COOKIES,
            "Cookie устанавливается без флага Secure",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Session Management",
            "Cookie без атрибута Secure передаётся браузером и по незашифрованному HTTP-соединению, "
                    + "что позволяет перехватить её содержимое (включая идентификаторы и связанные с "
                    + "пользователем данные) на промежуточных узлах сети.",
            "Установите атрибут Secure для всех cookie. Для cookie сессии и аутентификации также "
                    + "задайте HttpOnly и SameSite.",
            "Cookie устанавливаются с флагом Secure",
            "Все наблюдённые cookie помечены флагом Secure.");

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
    public boolean appliesTo(ScanAnalysisContext ctx) {
        return CookieSupport.cookiesSnapshotAvailable(ctx);
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<PageAnalysisResult> pages = ctx.pages() == null ? List.of() : ctx.pages();
        List<RuleFact> facts = new ArrayList<>();
        for (PageAnalysisResult page : pages) {
            if (page.preConsentCookies() == null || page.preConsentCookies().isEmpty()) {
                continue;
            }
            Set<String> insecure = new LinkedHashSet<>();
            for (ObservedCookie c : page.preConsentCookies()) {
                if (!c.secure()) {
                    insecure.add(c.name());
                }
            }
            if (insecure.isEmpty()) {
                continue;
            }
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "На странице " + HttpHeaderSupport.shortUrl(page.url())
                            + " cookie без флага Secure: " + String.join(", ", insecure) + ".",
                    page.url(),
                    SourceType.HTML,
                    EvidenceType.DYNAMIC_RENDER,
                    0.90,
                    "cookie-without-secure;" + String.join(",", insecure),
                    VerificationStatus.DETECTED));
        }
        return facts;
    }
}
