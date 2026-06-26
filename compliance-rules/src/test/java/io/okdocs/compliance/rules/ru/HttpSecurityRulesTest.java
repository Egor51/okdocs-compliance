package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.rules.common.MissingCspRule;
import io.okdocs.compliance.rules.common.MissingHstsRule;
import io.okdocs.compliance.rules.common.WeakCspRule;
import io.okdocs.compliance.rules.common.WildcardCorsRule;

import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты 9 security-header правил Этапа 1. Общие инварианты (категория SECURITY, sourceType
 * HTTP_HEADER, переживание {@code technical == null}) проверяются параметрически, специфика каждого
 * правила — в @Nested-блоках.
 */
class HttpSecurityRulesTest {

    private static final String URL = "https://site.ru/";

    private static List<Rule> allRules() {
        return List.of(
                new MissingHstsRule(), new MissingCspRule(), new WeakCspRule(),
                new MissingReferrerPolicyRule(), new MissingXContentTypeOptionsRule(),
                new MissingFrameProtectionRule(), new WildcardCorsRule(),
                new SensitivePageCacheableRule(), new TechStackDisclosureRule());
    }

    /** Полный набор «правильных» заголовков: ни одно правило не должно сработать. */
    private static Map<String, String> secureHeaders() {
        return Map.of(
                "strict-transport-security", "max-age=31536000; includeSubDomains",
                "content-security-policy", "default-src 'self'; frame-ancestors 'none'",
                "referrer-policy", "no-referrer",
                "x-content-type-options", "nosniff",
                "x-frame-options", "DENY",
                "cache-control", "no-store");
    }

    @Test
    void allRulesAreSilentOnFullySecureHeaders() {
        var ctx = TestFixtures.ctxWithResponses(TestFixtures.response(URL, secureHeaders()));
        for (Rule rule : allRules()) {
            assertThat(rule.evaluate(ctx))
                    .as("%s must be silent on secure headers", rule.definition().code())
                    .isEmpty();
        }
    }

    @Test
    void allRulesSurviveNullTechnical() {
        // Регрессия: контекст без technical-паспорта (старые сканы/базовые RU-правила) — никаких NPE
        // и никаких находок.
        var ctx = TestFixtures.ctx(TestFixtures.simplePage("https://site.ru"));
        assertThat(ctx.technical()).isNull();
        for (Rule rule : allRules()) {
            assertThat(rule.evaluate(ctx))
                    .as("%s on null technical", rule.definition().code())
                    .isEmpty();
        }
    }

    @Test
    void allRulesAreSecurityCategory() {
        for (Rule rule : allRules()) {
            assertThat(rule.definition().category()).isEqualTo(FindingCategory.SECURITY);
        }
    }

    @Test
    void redirectHopsAreNotAnalyzed() {
        // Только 3xx-хоп без финального 200: security-заголовки не анализируются (нет тела/смысла).
        var ctx = TestFixtures.ctxWithResponses(
                TestFixtures.redirect("http://site.ru/", "https://site.ru/"));
        for (Rule rule : allRules()) {
            assertThat(rule.evaluate(ctx))
                    .as("%s on redirect-only", rule.definition().code())
                    .isEmpty();
        }
    }

    private static RuleFact single(List<RuleFact> facts) {
        assertThat(facts).hasSize(1);
        RuleFact f = facts.get(0);
        assertThat(f.sourceType()).isEqualTo(SourceType.HTTP_HEADER);
        assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.DETECTED);
        assertThat(f.sourceUrl()).isNotBlank();
        return f;
    }

    private static Map<String, String> without(String missing) {
        java.util.HashMap<String, String> h = new java.util.HashMap<>(secureHeaders());
        h.remove(missing);
        return h;
    }

    @Nested
    class Hsts {
        private final MissingHstsRule rule = new MissingHstsRule();

        @Test
        void flagsHttpsWithoutHsts() {
            var ctx = TestFixtures.ctxWithResponses(TestFixtures.response(URL, without("strict-transport-security")));
            assertThat(single(rule.evaluate(ctx)).code()).isEqualTo("MISSING_HSTS");
        }

        @Test
        void ignoresHttpResponses() {
            // На http HSTS браузером игнорируется — не наша находка (покрыто HTTPS_NOT_ENFORCED, Этап 2).
            var ctx = TestFixtures.ctxWithResponses(
                    TestFixtures.response("http://site.ru/", Map.of()));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }
    }

    @Nested
    class Csp {
        @Test
        void missingCspFlagged() {
            var ctx = TestFixtures.ctxWithResponses(TestFixtures.response(URL, without("content-security-policy")));
            assertThat(single(new MissingCspRule().evaluate(ctx)).code()).isEqualTo("MISSING_CSP");
        }

        @Test
        void weakCspFlagsUnsafeInline() {
            var ctx = TestFixtures.ctxWithResponses(TestFixtures.response(URL,
                    Map.of("content-security-policy", "default-src 'self'; script-src 'unsafe-inline'")));
            RuleFact f = single(new WeakCspRule().evaluate(ctx));
            assertThat(f.code()).isEqualTo("WEAK_CSP");
            assertThat(f.matchedSignals()).contains("unsafe-inline");
        }

        @Test
        void weakCspFlagsWildcardScriptSrc() {
            var ctx = TestFixtures.ctxWithResponses(TestFixtures.response(URL,
                    Map.of("content-security-policy", "script-src *")));
            assertThat(new WeakCspRule().evaluate(ctx)).singleElement()
                    .satisfies(f -> assertThat(f.matchedSignals()).contains("script-src *"));
        }

        @Test
        void weakCspSilentWhenNoCsp() {
            // Отсутствие CSP — это MissingCspRule, а не WeakCsp.
            var ctx = TestFixtures.ctxWithResponses(TestFixtures.response(URL, Map.of()));
            assertThat(new WeakCspRule().evaluate(ctx)).isEmpty();
        }
    }

    @Nested
    class SimpleMissingHeaders {
        @Test
        void referrerPolicy() {
            var ctx = TestFixtures.ctxWithResponses(TestFixtures.response(URL, without("referrer-policy")));
            assertThat(single(new MissingReferrerPolicyRule().evaluate(ctx)).code())
                    .isEqualTo("MISSING_REFERRER_POLICY");
        }

        @Test
        void xContentTypeOptions() {
            var ctx = TestFixtures.ctxWithResponses(TestFixtures.response(URL, without("x-content-type-options")));
            assertThat(single(new MissingXContentTypeOptionsRule().evaluate(ctx)).code())
                    .isEqualTo("MISSING_X_CONTENT_TYPE_OPTIONS");
        }
    }

    @Nested
    class FrameProtection {
        private final MissingFrameProtectionRule rule = new MissingFrameProtectionRule();

        @Test
        void flaggedWhenNeitherXfoNorFrameAncestors() {
            var noFrame = without("x-frame-options");
            noFrame.put("content-security-policy", "default-src 'self'"); // без frame-ancestors
            var ctx = TestFixtures.ctxWithResponses(TestFixtures.response(URL, noFrame));
            assertThat(single(rule.evaluate(ctx)).code()).isEqualTo("MISSING_FRAME_PROTECTION");
        }

        @Test
        void satisfiedByCspFrameAncestors() {
            var ctx = TestFixtures.ctxWithResponses(TestFixtures.response(URL,
                    Map.of("content-security-policy", "frame-ancestors 'none'")));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }
    }

    @Nested
    class Cors {
        private final WildcardCorsRule rule = new WildcardCorsRule();

        @Test
        void flagsWildcardOrigin() {
            var ctx = TestFixtures.ctxWithResponses(TestFixtures.response(URL,
                    Map.of("access-control-allow-origin", "*")));
            assertThat(single(rule.evaluate(ctx)).code()).isEqualTo("WILDCARD_CORS");
        }

        @Test
        void higherConfidenceWithCredentials() {
            var ctx = TestFixtures.ctxWithResponses(TestFixtures.response(URL, Map.of(
                    "access-control-allow-origin", "*",
                    "access-control-allow-credentials", "true")));
            assertThat(rule.evaluate(ctx)).singleElement().satisfies(f -> {
                assertThat(f.confidence()).isEqualTo(0.95);
                assertThat(f.matchedSignals()).contains("with-credentials");
            });
        }

        @Test
        void ignoresSpecificOrigin() {
            var ctx = TestFixtures.ctxWithResponses(TestFixtures.response(URL,
                    Map.of("access-control-allow-origin", "https://trusted.ru")));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }
    }

    @Nested
    class CacheableSensitivePage {
        private final SensitivePageCacheableRule rule = new SensitivePageCacheableRule();

        @Test
        void flagsLoginPageWithoutNoStore() {
            var ctx = TestFixtures.ctxWithResponses(
                    TestFixtures.response("https://site.ru/login", Map.of()));
            assertThat(single(rule.evaluate(ctx)).code()).isEqualTo("SENSITIVE_PAGE_CACHEABLE");
        }

        @Test
        void ignoresNonSensitivePage() {
            var ctx = TestFixtures.ctxWithResponses(
                    TestFixtures.response("https://site.ru/about", Map.of()));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }

        @Test
        void satisfiedByNoStore() {
            var ctx = TestFixtures.ctxWithResponses(
                    TestFixtures.response("https://site.ru/checkout", Map.of("cache-control", "no-store")));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }
    }

    @Nested
    class TechStack {
        private final TechStackDisclosureRule rule = new TechStackDisclosureRule();

        @Test
        void flagsServerWithVersion() {
            var ctx = TestFixtures.ctxWithResponses(
                    TestFixtures.response(URL, Map.of("server", "nginx/1.18.0")));
            RuleFact f = single(rule.evaluate(ctx));
            assertThat(f.code()).isEqualTo("TECH_STACK_DISCLOSURE");
            assertThat(f.evidence()).contains("nginx/1.18.0");
        }

        @Test
        void ignoresServerWithoutVersion() {
            var ctx = TestFixtures.ctxWithResponses(
                    TestFixtures.response(URL, Map.of("server", "nginx")));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }

        @Test
        void flagsXPoweredByEvenWithoutVersion() {
            var ctx = TestFixtures.ctxWithResponses(
                    TestFixtures.response(URL, Map.of("x-powered-by", "PHP")));
            assertThat(rule.evaluate(ctx)).hasSize(1);
        }
    }

    @Test
    void perPageEvidenceAddressesCorrectUrl() {
        // Несколько финальных ответов: находка адресуется к своему URL.
        var ctx = TestFixtures.ctxWithResponses(
                TestFixtures.response("https://site.ru/login", without("content-security-policy")),
                TestFixtures.response("https://site.ru/", secureHeaders()));
        List<RuleFact> facts = new MissingCspRule().evaluate(ctx);
        assertThat(facts).singleElement()
                .satisfies(f -> assertThat(f.sourceUrl()).isEqualTo("https://site.ru/login"));
    }

    @Test
    void everyRuleCodeIsUnique() {
        List<String> codes = allRules().stream().map(r -> r.definition().code()).toList();
        assertThat(codes).doesNotHaveDuplicates();
        assertThat(Stream.of("MISSING_HSTS", "MISSING_CSP", "WEAK_CSP", "MISSING_REFERRER_POLICY",
                "MISSING_X_CONTENT_TYPE_OPTIONS", "MISSING_FRAME_PROTECTION", "WILDCARD_CORS",
                "SENSITIVE_PAGE_CACHEABLE", "TECH_STACK_DISCLOSURE")).allMatch(codes::contains);
    }
}
