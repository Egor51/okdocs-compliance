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
 * Legacy-код {@code NO_COOKIE_CONSENT}: найдены трекеры/аналитика, но автоматическая проверка не
 * смогла определить раскрытый механизм выбора или иное правовое основание. Для RU отсутствие
 * баннера само по себе не является нарушением, поэтому результат всегда {@code UNVERIFIED} и не
 * влияет на risk score.
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
            "Не определено правовое основание использования аналитических идентификаторов",
            null,
            "ст. 6, ст. 9 (если используется согласие), ст. 15 и ст. 18.1 152-ФЗ",
            "На сайте обнаружен известный аналитический или маркетинговый сервис, но сканер не нашёл "
                    + "механизм выбора пользователя. Для российского профиля это сигнал для проверки, "
                    + "а не установленное нарушение: необходимо определить состав данных, цель, получателя "
                    + "и применимое основание обработки.",
            "1. Проведите инвентаризацию cookies, storage и внешних запросов. 2. Определите цель и "
                    + "правовое основание каждого сервиса. 3. Раскройте обработку в политике. 4. Если "
                    + "оператор опирается на согласие, блокируйте соответствующую обработку до выбора и "
                    + "обеспечьте отказ/отзыв.",
            "Сочетание известного трекера и отсутствующего механизма выбора не обнаружено",
            "На обследованных страницах не найдено именно это сочетание технических сигналов. Это не означает, что правовое "
                    + "основание всей cookie- и storage-обработки подтверждено.");

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
                "Найден известный трекер/аналитика, но механизм выбора или иное правовое основание "
                        + "автоматически не определены. Требуется юридический контекст.",
                trackerUrl,
                SourceType.HTML,
                RuleSupport.evidenceType(ctx),
                null,
                "tracker-present;choice-mechanism-not-found;legal-basis-not-evaluated",
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
