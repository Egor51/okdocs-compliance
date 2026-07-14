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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Сессионная cookie выставлена без флага {@code HttpOnly}: доступна из JavaScript, что при XSS даёт
 * угон сессии. Назначение определяется консервативно по имени, характерному для идентификатора
 * сессии/авторизации (sessid/PHPSESSID/JSESSIONID/sid/auth/token). Сам по себе
 * {@link ObservedCookie#session()} означает лишь срок жизни до закрытия браузера и не доказывает
 * auth-назначение: locale/theme cookie часто имеют такой же срок. Только DYNAMIC (атрибуты cookie).
 */
public final class SessionCookieWithoutHttpOnlyRule implements Rule {

    /** Имена, характерные для cookie сессии/аутентификации. */
    private static final Pattern SESSION_NAME = Pattern.compile(
            "(sess|phpsessid|jsessionid|asp\\.net_sessionid|\\bsid\\b|auth|token|login|remember)",
            Pattern.CASE_INSENSITIVE);

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "SESSION_COOKIE_WITHOUT_HTTPONLY",
            ScanJurisdiction.RU,
            FindingSeverity.HIGH,
            FindingCategory.COOKIES,
            "Сессионная cookie доступна из JavaScript (нет HttpOnly)",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Session Management",
            "Cookie сессии или аутентификации без атрибута HttpOnly доступна клиентскому JavaScript. "
                    + "При уязвимости XSS злоумышленник может прочитать её и угнать сессию пользователя, "
                    + "получив доступ к его персональным данным в личном кабинете.",
            "Установите HttpOnly для всех cookie сессии и аутентификации (а также Secure и SameSite). "
                    + "HttpOnly закрывает доступ к cookie из JavaScript.",
            "Сессионные cookie защищены HttpOnly",
            "Среди наблюдённых cookie с именами, характерными для сессии или авторизации, все "
                    + "помечены флагом HttpOnly.");

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
            Set<String> exposed = new LinkedHashSet<>();
            for (ObservedCookie c : page.preConsentCookies()) {
                if (isSessionLike(c) && !c.httpOnly()) {
                    exposed.add(c.name());
                }
            }
            if (exposed.isEmpty()) {
                continue;
            }
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "На странице " + HttpHeaderSupport.shortUrl(page.url())
                            + " сессионная cookie без HttpOnly: " + String.join(", ", exposed) + ".",
                    page.url(),
                    SourceType.HTML,
                    EvidenceType.DYNAMIC_RENDER,
                    0.85,
                    "session-cookie-without-httponly;" + String.join(",", exposed),
                    VerificationStatus.DETECTED,
                    "SESSION_COOKIE_WITHOUT_HTTPONLY",
                    java.util.Map.of("page", HttpHeaderSupport.shortUrl(page.url()), "items", exposed)));
        }
        return facts;
    }

    /** Сессионная/auth-cookie только по сильному сигналу имени; browser-session lifetime недостаточно. */
    private static boolean isSessionLike(ObservedCookie c) {
        String name = c.name() == null ? "" : c.name().toLowerCase(Locale.ROOT);
        return SESSION_NAME.matcher(name).find();
    }
}
