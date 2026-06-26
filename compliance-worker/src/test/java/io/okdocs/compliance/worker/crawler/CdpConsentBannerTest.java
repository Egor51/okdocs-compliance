package io.okdocs.compliance.worker.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.contracts.crawler.ConsentBannerInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Маппинг JSON структуры баннера ({@code __okdocksConsent.inspect()}) в {@link ConsentBannerInfo}. */
class CdpConsentBannerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ConsentBannerInfo parse(String json) throws Exception {
        JsonNode node = MAPPER.readTree(json);
        return CdpDynamicCrawler.parseBannerInfo(node);
    }

    @Test
    void bannerNotFoundMapsToNotFound() throws Exception {
        ConsentBannerInfo b = parse("{\"bannerFound\":false}");
        assertThat(b.bannerFound()).isFalse();
        assertThat(b.cmpProvider()).isNull();
    }

    @Test
    void fullBannerMapsAllSignals() throws Exception {
        ConsentBannerInfo b = parse("{\"bannerFound\":true,\"acceptButtonFound\":true,"
                + "\"rejectButtonFound\":true,\"manageButtonFound\":true,\"savePreferencesFound\":false,"
                + "\"rejectSameLevelAsAccept\":true,\"precheckedToggles\":true,\"cmpProvider\":\"OneTrust\"}");
        assertThat(b.bannerFound()).isTrue();
        assertThat(b.acceptButtonFound()).isTrue();
        assertThat(b.rejectButtonFound()).isTrue();
        assertThat(b.manageButtonFound()).isTrue();
        assertThat(b.rejectSameLevelAsAccept()).isTrue();
        assertThat(b.precheckedToggles()).isTrue();
        assertThat(b.cmpProvider()).isEqualTo("OneTrust");
    }

    @Test
    void rejectMissingDarkPattern() throws Exception {
        // Баннер с Accept, но без Reject на том же уровне — классический dark pattern.
        ConsentBannerInfo b = parse("{\"bannerFound\":true,\"acceptButtonFound\":true,"
                + "\"rejectButtonFound\":false,\"rejectSameLevelAsAccept\":false,\"cmpProvider\":null}");
        assertThat(b.acceptButtonFound()).isTrue();
        assertThat(b.rejectButtonFound()).isFalse();
        assertThat(b.rejectSameLevelAsAccept()).isFalse();
        assertThat(b.cmpProvider()).isNull();
    }

    @Test
    void nullCmpStaysNull() throws Exception {
        ConsentBannerInfo b = parse("{\"bannerFound\":true,\"cmpProvider\":null}");
        assertThat(b.cmpProvider()).isNull();
    }

    @Test
    void nullNodeMapsToNotFound() {
        assertThat(CdpDynamicCrawler.parseBannerInfo(null).bannerFound()).isFalse();
    }
}
