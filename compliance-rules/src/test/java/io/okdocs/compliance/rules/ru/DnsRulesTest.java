package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit-тесты 5 DNS-правил Этапа 3 (HOSTING). */
class DnsRulesTest {

    private static List<Rule> allRules() {
        return List.of(
                new DnsForeignWebInfrastructureRule(), new DnsForeignMailProviderRule(),
                new DnsCnameToForeignCloudRule(), new DnsMulticountryHostingRule(),
                new DnsLookupFailedRule());
    }

    @Test
    void allRulesAreHostingCategory() {
        for (Rule r : allRules()) {
            assertThat(r.definition().category()).isEqualTo(FindingCategory.HOSTING);
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

    @Test
    void allRulesSourceTypeDns() {
        // Любая находка DNS-правил помечается SourceType.DNS.
        var foreign = TestFixtures.ctxWithDns(
                TestFixtures.dns(List.of("8.8.8.8"), List.of("US"), List.of("x.amazonaws.com"), List.of("US")));
        for (Rule r : allRules()) {
            r.evaluate(foreign).forEach(f -> assertThat(f.sourceType()).isEqualTo(SourceType.DNS));
        }
    }

    @Nested
    class ForeignWeb {
        private final DnsForeignWebInfrastructureRule rule = new DnsForeignWebInfrastructureRule();

        @Test
        void flagsForeignIp() {
            var ctx = TestFixtures.ctxWithDns(
                    TestFixtures.dns(List.of("8.8.8.8"), List.of("US"), List.of(), List.of()));
            assertThat(rule.evaluate(ctx)).singleElement()
                    .satisfies(f -> assertThat(f.code()).isEqualTo("DNS_FOREIGN_WEB_INFRASTRUCTURE"));
        }

        @Test
        void silentForRuOnly() {
            var ctx = TestFixtures.ctxWithDns(
                    TestFixtures.dns(List.of("95.213.0.1"), List.of("RU"), List.of(), List.of()));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }
    }

    @Nested
    class ForeignMail {
        private final DnsForeignMailProviderRule rule = new DnsForeignMailProviderRule();

        @Test
        void flagsForeignMx() {
            var ctx = TestFixtures.ctxWithDns(
                    TestFixtures.dns(List.of("95.213.0.1"), List.of("RU"), List.of(), List.of("US")));
            assertThat(rule.evaluate(ctx)).singleElement()
                    .satisfies(f -> assertThat(f.code()).isEqualTo("DNS_FOREIGN_MAIL_PROVIDER"));
        }

        @Test
        void silentForRuMail() {
            var ctx = TestFixtures.ctxWithDns(
                    TestFixtures.dns(List.of("95.213.0.1"), List.of("RU"), List.of(), List.of("RU")));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }
    }

    @Nested
    class CnameForeignCloud {
        private final DnsCnameToForeignCloudRule rule = new DnsCnameToForeignCloudRule();

        @Test
        void flagsAwsCname() {
            var ctx = TestFixtures.ctxWithDns(TestFixtures.dns(
                    List.of("95.213.0.1"), List.of("RU"), List.of("d111.cloudfront.net"), List.of()));
            assertThat(rule.evaluate(ctx)).singleElement().satisfies(f -> {
                assertThat(f.code()).isEqualTo("DNS_CNAME_TO_FOREIGN_CLOUD");
                assertThat(f.matchedSignals()).contains("cloudfront.net");
            });
        }

        @Test
        void silentForRuCname() {
            var ctx = TestFixtures.ctxWithDns(TestFixtures.dns(
                    List.of("95.213.0.1"), List.of("RU"), List.of("cdn.site.ru"), List.of()));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }
    }

    @Nested
    class Multicountry {
        private final DnsMulticountryHostingRule rule = new DnsMulticountryHostingRule();

        @Test
        void flagsTwoCountries() {
            var ctx = TestFixtures.ctxWithDns(TestFixtures.dns(
                    List.of("95.213.0.1", "8.8.8.8"), List.of("RU", "US"), List.of(), List.of()));
            assertThat(rule.evaluate(ctx)).singleElement()
                    .satisfies(f -> assertThat(f.code()).isEqualTo("DNS_MULTICOUNTRY_HOSTING"));
        }

        @Test
        void silentForSingleCountry() {
            var ctx = TestFixtures.ctxWithDns(TestFixtures.dns(
                    List.of("95.213.0.1", "95.213.0.2"), List.of("RU", "RU"), List.of(), List.of()));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }
    }

    @Nested
    class LookupFailed {
        private final DnsLookupFailedRule rule = new DnsLookupFailedRule();

        @Test
        void flagsLookupFailureAsUnverified() {
            var ctx = TestFixtures.ctxWithDns(TestFixtures.dnsFailed());
            assertThat(rule.evaluate(ctx)).singleElement().satisfies(f -> {
                assertThat(f.code()).isEqualTo("DNS_LOOKUP_FAILED");
                assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
            });
        }

        @Test
        void silentOnSuccessfulLookup() {
            var ctx = TestFixtures.ctxWithDns(
                    TestFixtures.dns(List.of("95.213.0.1"), List.of("RU"), List.of(), List.of()));
            assertThat(rule.evaluate(ctx)).isEmpty();
        }
    }
}
