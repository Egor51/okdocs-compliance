package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.ObservedCookie;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Сессионная cookie выставлена без флага {@code HttpOnly}: доступна из JavaScript, что при XSS даёт
 * угон сессии. «Сессионная» определяется как cookie без срока истечения ({@link ObservedCookie#session()})
 * ИЛИ с именем, похожим на идентификатор сессии (sessid/PHPSESSID/JSESSIONID/sid/auth/token). Только
 * DYNAMIC (атрибуты cookie). Категория COOKIES, основание — ст. 19 152-ФЗ + OWASP Session Management.
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
            "Среди наблюдённых cookie все сессионные помечены флагом HttpOnly.");

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
                    VerificationStatus.DETECTED));
        }
        return facts;
    }

    /** Сессионная по флагу session (нет expires) или по имени, похожему на сессию/аутентификацию. */
    private static boolean isSessionLike(ObservedCookie c) {
        if (c.session()) {
            return true;
        }
        String name = c.name() == null ? "" : c.name().toLowerCase(Locale.ROOT);
        return SESSION_NAME.matcher(name).find();
    }
}
