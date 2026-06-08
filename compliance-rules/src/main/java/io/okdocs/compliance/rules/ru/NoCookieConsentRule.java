package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleDefinition;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.RuleSupport;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Найдены трекеры/аналитика, но нет cookie-баннера ни на одной странице. Метаданные перенесены
 * из MVP (okdocks {@code NO_COOKIE_CONSENT}).
 * <p>
 * Детекция трекера двусигнальная, чтобы не зависеть от полноты html (замечание P1): сначала по
 * нормализованным доменам краулера ({@link RuleSupport#externalScripts} ⋈ {@link RuTrackerDomains#ALL}),
 * затем — как fallback — regex-маркер трекинг-скрипта в html. Если домены урезаны, сработает regex,
 * и наоборот. Баннер — {@link RuPatterns#hasCookieBanner}.
 */
public final class NoCookieConsentRule implements Rule {

    private static final Pattern TRACKING_SCRIPT = Pattern.compile(
            "(mc\\.yandex\\.ru/metrika|googletagmanager\\.com|google-analytics\\.com|gtag/js"
                    + "|fbevents\\.js|vk\\.com/rtrg)",
            Pattern.CASE_INSENSITIVE);

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "NO_COOKIE_CONSENT",
            ScanJurisdiction.RU,
            FindingSeverity.MEDIUM,
            FindingCategory.COOKIES,
            "Cookie или аналитические идентификаторы используются без согласия пользователя",
            "150 000 – 300 000 ₽ для юрлиц; при повторном нарушении 300 000 – 500 000 ₽ для юрлиц",
            "ст. 13.11 ч. 1 и ч. 1.1 КоАП РФ, ст. 6, ст. 9 152-ФЗ",
            "Cookie и аналитические идентификаторы могут признаваться персональными данными, если "
                    + "позволяют прямо или косвенно идентифицировать пользователя. В таком случае требуется "
                    + "правовое основание обработки, а для необязательной аналитики, рекламы и маркетинга — "
                    + "согласие пользователя.",
            "1. Установите cookie-баннер. 2. Разделите обязательные, аналитические, рекламные и "
                    + "маркетинговые cookie. 3. До получения согласия не запускайте необязательную "
                    + "аналитику и сторонние трекеры. 4. Дайте возможность отказаться от необязательных "
                    + "cookie. 5. Отразите использование cookie в политике обработки ПДн.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<PageAnalysisResult> pages = ctx.pages() == null ? List.of() : ctx.pages();
        if (pages.isEmpty()) {
            return List.of();
        }

        String trackerUrl = findTracker(ctx, pages);
        if (trackerUrl == null) {
            // нет наблюдаемого трекера — нечего вменять про cookie без баннера
            return List.of();
        }
        if (RuPatterns.hasCookieBanner(ctx)) {
            return List.of();
        }

        return List.of(new RuleFact(
                DEFINITION.code(),
                "Найдены трекеры/аналитика, но нет cookie-баннера ни на одной из проверенных страниц.",
                trackerUrl,
                SourceType.HTML,
                RuleSupport.evidenceType(ctx),
                null,
                "tracker-present;cookie-banner-absent",
                VerificationStatus.UNVERIFIED));
    }

    /** URL страницы с первым обнаруженным трекером (домен или regex), либо null. */
    private static String findTracker(ScanAnalysisContext ctx, List<PageAnalysisResult> pages) {
        for (PageAnalysisResult p : pages) {
            if (matchesByDomain(p) || (p.html() != null && TRACKING_SCRIPT.matcher(p.html()).find())) {
                return p.url();
            }
        }
        return null;
    }

    private static boolean matchesByDomain(PageAnalysisResult p) {
        if (p.externalScriptDomains() == null) {
            return false;
        }
        for (String domain : p.externalScriptDomains()) {
            for (String tracker : RuTrackerDomains.ALL) {
                if (RuleSupport.domainMatches(domain, tracker)) {
                    return true;
                }
            }
        }
        return false;
    }
}
