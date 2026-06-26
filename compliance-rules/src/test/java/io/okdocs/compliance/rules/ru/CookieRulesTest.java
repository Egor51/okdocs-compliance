package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.rules.common.CookieWithoutSecureFlagRule;
import io.okdocs.compliance.rules.common.LocalStorageTrackingBeforeConsentRule;
import io.okdocs.compliance.rules.common.SessionCookieWithoutHttpOnlyRule;
import io.okdocs.compliance.rules.common.TrackingCookiesBeforeConsentRule;

import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit-тесты 4 cookie-правил Этапа 4 (Phase 1, COOKIES). */
class CookieRulesTest {

    private static List<Rule> allRules() {
        return List.of(
                new TrackingCookiesBeforeConsentRule(), new LocalStorageTrackingBeforeConsentRule(),
                new CookieWithoutSecureFlagRule(), new SessionCookieWithoutHttpOnlyRule());
    }

    @Test
    void allRulesAreCookiesCategory() {
        for (Rule r : allRules()) {
            assertThat(r.definition().category()).isEqualTo(FindingCategory.COOKIES);
        }
    }

    @Test
    void allRulesSurviveNullTechnicalAndStaticPages() {
        // STATIC-страница: pre-consent cookies/storage пусты → ни одно правило не срабатывает.
        var ctx = TestFixtures.ctx(TestFixtures.simplePage("https://site.ru"));
        for (Rule r : allRules()) {
            assertThat(r.evaluate(ctx)).as(r.definition().code()).isEmpty();
        }
    }

    @Test
    void rulesDoNotApplyWithoutSnapshots() {
        // Нет собранных cookies/storage (DYNAMIC не запускался/деградировал) → appliesTo=false:
        // правило НЕ должно давать PASSED, иначе отчёт солжёт «нарушений нет». RuleEngine пометит
        // NOT_EVALUATED. Проверяем appliesTo напрямую на STATIC-странице.
        var ctx = TestFixtures.ctx(TestFixtures.simplePage("https://site.ru"));
        for (Rule r : allRules()) {
            assertThat(r.appliesTo(ctx)).as("%s applies on static (no cookies)", r.definition().code()).isFalse();
        }
    }

    @Test
    void rulesApplyWhenSnapshotsAvailable() {
        var page = TestFixtures.dynamicPageWithCookies("https://site.ru/",
                List.of(TestFixtures.cookie("any", true, true)), List.of("any_key"));
        var ctx = TestFixtures.ctx(page);
        for (Rule r : allRules()) {
            assertThat(r.appliesTo(ctx)).as(r.definition().code()).isTrue();
        }
    }

    @Test
    void rulesApplyWhenSnapshotsAreAvailableButEmpty() {
        // DYNAMIC успешно снял snapshots, но cookies/storage реально пустые → правила применимы и
        // RuleEngine сможет показать PASSED, а не NOT_EVALUATED.
        var page = TestFixtures.dynamicPageWithCookies("https://site.ru/", List.of(), List.of());
        var ctx = TestFixtures.ctx(page);
        for (Rule r : allRules()) {
            assertThat(r.appliesTo(ctx)).as(r.definition().code()).isTrue();
            assertThat(r.evaluate(ctx)).as(r.definition().code()).isEmpty();
        }
    }

    @Test
    void compatibleDynamicConstructorDoesNotImplySnapshots() {
        var page = new io.okdocs.compliance.contracts.crawler.PageAnalysisResult(
                "https://site.ru/", "title", "text", "<html></html>",
                List.of(), List.of(), List.of(), false, List.of(),
                io.okdocs.compliance.contracts.enums.RenderMode.DYNAMIC,
                List.of(), List.of(), List.of());
        var ctx = TestFixtures.ctx(page);
        for (Rule r : allRules()) {
            assertThat(r.appliesTo(ctx)).as(r.definition().code()).isFalse();
        }
    }

    @Nested
    class TrackingCookies {
        private final TrackingCookiesBeforeConsentRule rule = new TrackingCookiesBeforeConsentRule();

        @Test
        void flagsTrackerCookieBeforeConsent() {
            var page = TestFixtures.dynamicPageWithCookies("https://site.ru/",
                    List.of(TestFixtures.cookie("_ga", true, false)), List.of());
            assertThat(rule.evaluate(TestFixtures.ctx(page))).singleElement().satisfies(f -> {
                assertThat(f.code()).isEqualTo("TRACKING_COOKIES_BEFORE_CONSENT");
                assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.CONFIRMED);
                assertThat(f.matchedSignals()).contains("_ga");
            });
        }

        @Test
        void silentForNonTrackerCookie() {
            var page = TestFixtures.dynamicPageWithCookies("https://site.ru/",
                    List.of(TestFixtures.cookie("cart_id", true, true)), List.of());
            assertThat(rule.evaluate(TestFixtures.ctx(page))).isEmpty();
        }
    }

    @Nested
    class LocalStorageTracking {
        private final LocalStorageTrackingBeforeConsentRule rule = new LocalStorageTrackingBeforeConsentRule();

        @Test
        void flagsTrackerStorageKey() {
            var page = TestFixtures.dynamicPageWithCookies("https://site.ru/",
                    List.of(), List.of("amplitude_id", "theme"));
            assertThat(rule.evaluate(TestFixtures.ctx(page))).singleElement().satisfies(f -> {
                assertThat(f.code()).isEqualTo("LOCAL_STORAGE_TRACKING_BEFORE_CONSENT");
                assertThat(f.matchedSignals()).contains("amplitude_id");
            });
        }

        @Test
        void silentForNonTrackerKeys() {
            var page = TestFixtures.dynamicPageWithCookies("https://site.ru/",
                    List.of(), List.of("theme", "lang"));
            assertThat(rule.evaluate(TestFixtures.ctx(page))).isEmpty();
        }
    }

    @Nested
    class WithoutSecure {
        private final CookieWithoutSecureFlagRule rule = new CookieWithoutSecureFlagRule();

        @Test
        void flagsCookieMissingSecure() {
            var page = TestFixtures.dynamicPageWithCookies("https://site.ru/",
                    List.of(TestFixtures.cookie("cart", false, true)), List.of());
            assertThat(rule.evaluate(TestFixtures.ctx(page))).singleElement()
                    .satisfies(f -> assertThat(f.code()).isEqualTo("COOKIE_WITHOUT_SECURE_FLAG"));
        }

        @Test
        void silentWhenAllSecure() {
            var page = TestFixtures.dynamicPageWithCookies("https://site.ru/",
                    List.of(TestFixtures.cookie("cart", true, true)), List.of());
            assertThat(rule.evaluate(TestFixtures.ctx(page))).isEmpty();
        }
    }

    @Nested
    class SessionWithoutHttpOnly {
        private final SessionCookieWithoutHttpOnlyRule rule = new SessionCookieWithoutHttpOnlyRule();

        @Test
        void flagsSessionCookieWithoutHttpOnly() {
            var page = TestFixtures.dynamicPageWithCookies("https://site.ru/",
                    List.of(TestFixtures.sessionCookie("connect.sid", true, false)), List.of());
            assertThat(rule.evaluate(TestFixtures.ctx(page))).singleElement()
                    .satisfies(f -> assertThat(f.code()).isEqualTo("SESSION_COOKIE_WITHOUT_HTTPONLY"));
        }

        @Test
        void flagsSessionNamedCookieEvenIfPersistent() {
            // Имя похоже на сессию (PHPSESSID), хотя cookie persistent (session=false) → всё равно ловим.
            var page = TestFixtures.dynamicPageWithCookies("https://site.ru/",
                    List.of(TestFixtures.cookie("PHPSESSID", true, false)), List.of());
            assertThat(rule.evaluate(TestFixtures.ctx(page))).isNotEmpty();
        }

        @Test
        void silentForNonSessionWithoutHttpOnly() {
            var page = TestFixtures.dynamicPageWithCookies("https://site.ru/",
                    List.of(TestFixtures.cookie("theme", true, false)), List.of());
            assertThat(rule.evaluate(TestFixtures.ctx(page))).isEmpty();
        }

        @Test
        void silentWhenSessionCookieHasHttpOnly() {
            var page = TestFixtures.dynamicPageWithCookies("https://site.ru/",
                    List.of(TestFixtures.sessionCookie("connect.sid", true, true)), List.of());
            assertThat(rule.evaluate(TestFixtures.ctx(page))).isEmpty();
        }
    }
}
