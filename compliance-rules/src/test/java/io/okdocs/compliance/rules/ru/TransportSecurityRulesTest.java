package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.rules.common.HttpsNotEnforcedRule;
import io.okdocs.compliance.rules.common.MixedContentRule;
import io.okdocs.compliance.rules.common.TlsCertificateExpiresSoonRule;
import io.okdocs.compliance.rules.common.TlsCertificateInvalidRule;

import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit-тесты 7 правил Этапа 2 (TLS + транспорт). */
class TransportSecurityRulesTest {

    private static List<Rule> allRules() {
        return List.of(
                new HttpsNotEnforcedRule(), new HttpFormActionRule(), new MixedContentRule(),
                new TlsCertificateInvalidRule(), new TlsCertificateExpiresSoonRule(),
                new TlsHostnameMismatchRule(), new TlsLegacyProtocolRule());
    }

    @Test
    void allRulesAreSecurityCategory() {
        for (Rule r : allRules()) {
            assertThat(r.definition().category()).isEqualTo(FindingCategory.SECURITY);
        }
    }

    @Test
    void allRulesSurviveNullTechnical() {
        var ctx = TestFixtures.ctx(TestFixtures.simplePage("https://site.ru"));
        assertThat(ctx.technical()).isNull();
        for (Rule r : allRules()) {
            assertThat(r.evaluate(ctx)).as(r.definition().code()).isEmpty();
        }
    }

    @Nested
    class HttpsNotEnforced {
        private final HttpsNotEnforcedRule rule = new HttpsNotEnforcedRule();

        @Test
        void flagsHttp200() {
            var ctx = TestFixtures.ctxTechnical(
                    List.of(TestFixtures.simplePage("http://site.ru/")),
                    List.of(TestFixtures.response("http://site.ru/", java.util.Map.of())),
                    List.of());
            assertThat(rule.evaluate(ctx)).singleElement()
                    .satisfies(f -> assertThat(f.code()).isEqualTo("HTTPS_NOT_ENFORCED"));
        }

        @Test
        void silentWhenHttpRedirectsToHttps() {
            var ctx = TestFixtures.ctxTechnical(
                    List.of(TestFixtures.simplePage("https://site.ru/")),
                    List.of(TestFixtures.redirect("http://site.ru/", "https://site.ru/"),
                            TestFixtures.response("https://site.ru/", java.util.Map.of())),
                    List.of());
            assertThat(rule.evaluate(ctx)).isEmpty();
        }

        @Test
        void flagsHttpRedirectToHttp() {
            var ctx = TestFixtures.ctxTechnical(
                    List.of(TestFixtures.simplePage("http://site.ru/")),
                    List.of(TestFixtures.redirect("http://site.ru/", "http://www.site.ru/")),
                    List.of());
            assertThat(rule.evaluate(ctx)).isNotEmpty();
        }
    }

    @Nested
    class HttpFormAction {
        private final HttpFormActionRule rule = new HttpFormActionRule();

        @Test
        void flagsHttpActionOnPdForm() {
            var page = TestFixtures.page("https://site.ru/form", "форма", false,
                    List.of(TestFixtures.dataFormNoConsent("http://site.ru/submit")),
                    List.of(), List.of(), "<html/>");
            var ctx = TestFixtures.ctxTechnical(List.of(page), List.of(), List.of());
            assertThat(rule.evaluate(ctx)).singleElement().satisfies(f -> {
                assertThat(f.code()).isEqualTo("HTTP_FORM_ACTION");
                assertThat(f.sourceType()).isEqualTo(SourceType.HTML);
            });
        }

        @Test
        void silentWhenHttpsAction() {
            var page = TestFixtures.page("https://site.ru/form", "форма", false,
                    List.of(TestFixtures.dataFormNoConsent("https://site.ru/submit")),
                    List.of(), List.of(), "<html/>");
            assertThat(rule.evaluate(TestFixtures.ctxTechnical(List.of(page), List.of(), List.of()))).isEmpty();
        }
    }

    @Nested
    class MixedContent {
        private final MixedContentRule rule = new MixedContentRule();

        @Test
        void flagsHttpScriptOnHttpsPage() {
            var page = TestFixtures.page("https://site.ru/", "txt", false, List.of(), List.of(),
                    List.of(), "<html><body><script src=\"http://cdn.evil.com/a.js\"></script></body></html>");
            var ctx = TestFixtures.ctxTechnical(List.of(page), List.of(), List.of());
            assertThat(rule.evaluate(ctx)).singleElement().satisfies(f -> {
                assertThat(f.code()).isEqualTo("MIXED_CONTENT_DETECTED");
                assertThat(f.matchedSignals()).contains("cdn.evil.com");
            });
        }

        @Test
        void ignoresNavigationAnchors() {
            var page = TestFixtures.page("https://site.ru/", "txt", false, List.of(), List.of(),
                    List.of(), "<html><body><a href=\"http://other.ru/page\">link</a></body></html>");
            assertThat(rule.evaluate(TestFixtures.ctxTechnical(List.of(page), List.of(), List.of()))).isEmpty();
        }

        @Test
        void silentOnHttpPage() {
            var page = TestFixtures.page("http://site.ru/", "txt", false, List.of(), List.of(),
                    List.of(), "<html><script src=\"http://cdn.ru/a.js\"></script></html>");
            assertThat(rule.evaluate(TestFixtures.ctxTechnical(List.of(page), List.of(), List.of()))).isEmpty();
        }
    }

    @Nested
    class TlsCertInvalid {
        private final TlsCertificateInvalidRule rule = new TlsCertificateInvalidRule();

        @Test
        void certErrorIsConfirmedDetected() {
            var ctx = TestFixtures.ctxWithTls(
                    TestFixtures.tlsFailed("site.ru", "PKIX path building failed: unable to find valid certification path"));
            assertThat(rule.evaluate(ctx)).singleElement().satisfies(f -> {
                assertThat(f.code()).isEqualTo("TLS_CERTIFICATE_INVALID");
                assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.DETECTED);
                assertThat(f.sourceType()).isEqualTo(SourceType.TLS);
            });
        }

        @Test
        void networkErrorIsUnverified() {
            var ctx = TestFixtures.ctxWithTls(TestFixtures.tlsFailed("site.ru", "Connection timed out"));
            assertThat(rule.evaluate(ctx)).singleElement()
                    .satisfies(f -> assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED));
        }

        @Test
        void silentOnSuccessfulHandshake() {
            var ctx = TestFixtures.ctxWithTls(TestFixtures.tlsOk("site.ru", "TLSv1.3",
                    List.of("site.ru"), Instant.now().plus(200, ChronoUnit.DAYS)));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }

        @Test
        void silentOnHostnameMismatchWhenCertificateChainIsTrusted() {
            var ctx = TestFixtures.ctxWithTls(TestFixtures.tlsOk("site.ru", "TLSv1.3",
                    List.of("other.com"), Instant.now().plus(200, ChronoUnit.DAYS)));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }
    }

    @Nested
    class TlsExpiresSoon {
        private final TlsCertificateExpiresSoonRule rule = new TlsCertificateExpiresSoonRule();

        @Test
        void flagsCertExpiringWithin30Days() {
            var ctx = TestFixtures.ctxWithTls(TestFixtures.tlsOk("site.ru", "TLSv1.3",
                    List.of("site.ru"), Instant.now().plus(10, ChronoUnit.DAYS)));
            assertThat(rule.evaluate(ctx)).singleElement()
                    .satisfies(f -> assertThat(f.code()).isEqualTo("TLS_CERTIFICATE_EXPIRES_SOON"));
        }

        @Test
        void silentWhenFarFromExpiry() {
            var ctx = TestFixtures.ctxWithTls(TestFixtures.tlsOk("site.ru", "TLSv1.3",
                    List.of("site.ru"), Instant.now().plus(200, ChronoUnit.DAYS)));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }
    }

    @Nested
    class TlsHostnameMismatch {
        private final TlsHostnameMismatchRule rule = new TlsHostnameMismatchRule();

        @Test
        void flagsWhenHostNotInSan() {
            var ctx = TestFixtures.ctxWithTls(TestFixtures.tlsOk("site.ru", "TLSv1.3",
                    List.of("other.com", "www.other.com"), Instant.now().plus(100, ChronoUnit.DAYS)));
            assertThat(rule.evaluate(ctx)).singleElement()
                    .satisfies(f -> assertThat(f.code()).isEqualTo("TLS_HOSTNAME_MISMATCH"));
        }

        @Test
        void matchesWildcardSan() {
            var ctx = TestFixtures.ctxWithTls(TestFixtures.tlsOk("www.site.ru", "TLSv1.3",
                    List.of("*.site.ru"), Instant.now().plus(100, ChronoUnit.DAYS)));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }

        @Test
        void wildcardDoesNotMatchApex() {
            // *.site.ru НЕ покрывает site.ru
            var ctx = TestFixtures.ctxWithTls(TestFixtures.tlsOk("site.ru", "TLSv1.3",
                    List.of("*.site.ru"), Instant.now().plus(100, ChronoUnit.DAYS)));
            assertThat(rule.evaluate(ctx)).isNotEmpty();
        }
    }

    @Nested
    class TlsLegacyProtocol {
        private final TlsLegacyProtocolRule rule = new TlsLegacyProtocolRule();
        private final Instant later = Instant.now().plus(100, ChronoUnit.DAYS);

        @Test
        void flagsTls10() {
            var ctx = TestFixtures.ctxWithTls(TestFixtures.tlsOk("site.ru", "TLSv1",
                    List.of("site.ru"), later));
            assertThat(rule.evaluate(ctx)).singleElement()
                    .satisfies(f -> assertThat(f.code()).isEqualTo("TLS_LEGACY_PROTOCOL"));
        }

        @Test
        void silentOnTls13() {
            var ctx = TestFixtures.ctxWithTls(TestFixtures.tlsOk("site.ru", "TLSv1.3",
                    List.of("site.ru"), later));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }

        @Test
        void flagsLegacyEvenWhenNegotiatedIsModern() {
            // Корень бага: рукопожатие на TLS 1.3, но сервер всё ещё принимает TLS 1.0/1.1.
            // По protocol() это было бы PASSED — теперь смотрим supportedProtocols.
            var ctx = TestFixtures.ctxWithTls(TestFixtures.tlsOkSupporting("site.ru", "TLSv1.3",
                    List.of("site.ru"), later, List.of("TLSv1.3", "TLSv1.1", "TLSv1")));
            assertThat(rule.evaluate(ctx)).singleElement().satisfies(f -> {
                assertThat(f.code()).isEqualTo("TLS_LEGACY_PROTOCOL");
                assertThat(f.matchedSignals()).contains("TLSv1.1");
                assertThat(f.matchedSignals()).contains("TLSv1");
            });
        }

        @Test
        void notApplicableWhenProtocolsNotProbed() {
            // Старый снимок без probe (supportedProtocols == null): NOT_EVALUATED, а не ложный PASSED.
            // Компат-конструктор TlsInfo оставляет supportedProtocols = null — это и есть «не зондировали».
            var ctx = TestFixtures.ctxWithTls(TestFixtures.tlsFailed("site.ru", "irrelevant")
                    /* handshakeOk=false тоже не применимо; ниже — успешный без probe */);
            // Успешный handshake, но без probe-данных: applies должно быть false.
            var probedNull = new io.okdocs.compliance.contracts.crawler.TlsInfo(
                    "site.ru", true, null, "TLSv1.3", "TLS_AES_128_GCM_SHA256",
                    "CN=site.ru", "CN=CA", List.of("site.ru"),
                    Instant.now().minusSeconds(86400), later);
            var ctx2 = TestFixtures.ctxWithTls(probedNull);
            assertThat(rule.appliesTo(ctx2)).isFalse();
            assertThat(rule.appliesTo(ctx)).isFalse();
        }

        @Test
        void applicableWhenProbed() {
            var ctx = TestFixtures.ctxWithTls(TestFixtures.tlsOk("site.ru", "TLSv1.3",
                    List.of("site.ru"), later));
            assertThat(rule.appliesTo(ctx)).isTrue();
        }
    }
}
