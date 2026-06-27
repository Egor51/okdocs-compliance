package io.okdocs.compliance.rules.common;

import io.okdocs.compliance.rules.common.TrackerCatalog.TrackerInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrackerCatalogTest {

    @Test
    void resolvesExactDomain() {
        assertThat(TrackerCatalog.lookup("google-analytics.com"))
                .get().extracting(TrackerInfo::provider).isEqualTo("Google");
    }

    @Test
    void resolvesSubdomain() {
        assertThat(TrackerCatalog.lookup("www.google-analytics.com"))
                .get().extracting(TrackerInfo::country).isEqualTo("US");
    }

    @Test
    void unknownDomainEmpty() {
        assertThat(TrackerCatalog.lookup("cdn.self.example")).isEmpty();
    }

    @Test
    void usProviderIsThirdCountry() {
        TrackerInfo google = TrackerCatalog.lookup("google-analytics.com").orElseThrow();
        assertThat(TrackerCatalog.isThirdCountry(google)).isTrue();
    }

    @Test
    void euProviderIsNotThirdCountry() {
        // Hotjar (MT — Мальта, EU) — не третья страна.
        TrackerInfo hotjar = TrackerCatalog.lookup("hotjar.com").orElseThrow();
        assertThat(TrackerCatalog.isThirdCountry(hotjar)).isFalse();
    }

    @Test
    void nullDomainEmpty() {
        assertThat(TrackerCatalog.lookup(null)).isEmpty();
    }
}
