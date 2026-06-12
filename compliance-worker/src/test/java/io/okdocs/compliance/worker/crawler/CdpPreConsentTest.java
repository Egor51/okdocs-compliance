package io.okdocs.compliance.worker.crawler;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Табличный тест чистого ядра «трекер до согласия»
 * ({@link CdpDynamicCrawler#computePreConsentHosts}). Времена — в единой шкале epoch-мс
 * ({@code wallTime} запроса и {@code Date.now()} баннера), поэтому сравнимы напрямую.
 */
class CdpPreConsentTest {

    private static Map<String, Double> timeline(Object... hostThenTs) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < hostThenTs.length; i += 2) {
            m.put((String) hostThenTs[i], ((Number) hostThenTs[i + 1]).doubleValue());
        }
        return m;
    }

    @Test
    void requestBeforeBannerIsPreConsent() {
        Map<String, Double> tl = timeline("mc.yandex.ru", 1000.0);
        assertThat(CdpDynamicCrawler.computePreConsentHosts(tl, 2000.0, "site.ru"))
                .containsExactly("mc.yandex.ru");
    }

    @Test
    void requestAfterBannerIsNotPreConsent() {
        Map<String, Double> tl = timeline("mc.yandex.ru", 3000.0);
        assertThat(CdpDynamicCrawler.computePreConsentHosts(tl, 2000.0, "site.ru"))
                .isEmpty();
    }

    @Test
    void noBannerMeansEveryThirdPartyIsPreConsent() {
        // bannerEpochMs == null → баннера не было вовсе → любой сторонний запрос pre-consent.
        Map<String, Double> tl = timeline("mc.yandex.ru", 1000.0, "google-analytics.com", 5000.0);
        assertThat(CdpDynamicCrawler.computePreConsentHosts(tl, null, "site.ru"))
                .containsExactlyInAnyOrder("mc.yandex.ru", "google-analytics.com");
    }

    @Test
    void firstPartyHostExcluded() {
        Map<String, Double> tl = timeline(
                "site.ru", 500.0, "www.site.ru", 600.0, "mc.yandex.ru", 700.0);
        assertThat(CdpDynamicCrawler.computePreConsentHosts(tl, null, "site.ru"))
                .containsExactly("mc.yandex.ru");
    }

    @Test
    void resultsSortedByRequestTime() {
        Map<String, Double> tl = timeline(
                "b.example.com", 2000.0, "a.example.com", 1000.0, "c.example.com", 3000.0);
        assertThat(CdpDynamicCrawler.computePreConsentHosts(tl, null, "site.ru"))
                .containsExactly("a.example.com", "b.example.com", "c.example.com");
    }

    @Test
    void emptyTimelineYieldsEmptyList() {
        assertThat(CdpDynamicCrawler.computePreConsentHosts(Map.of(), 2000.0, "site.ru"))
                .isEmpty();
        assertThat(CdpDynamicCrawler.computePreConsentHosts(null, 2000.0, "site.ru"))
                .isEmpty();
    }

    @Test
    void nullAllowedDomainKeepsAllNonFirstPartyHosts() {
        Map<String, Double> tl = timeline("mc.yandex.ru", 1000.0);
        assertThat(CdpDynamicCrawler.computePreConsentHosts(tl, null, null))
                .containsExactly("mc.yandex.ru");
    }

    @Test
    void mixedBeforeAndAfterBanner() {
        Map<String, Double> tl = timeline(
                "mc.yandex.ru", 1000.0, "google-analytics.com", 3000.0, "hotjar.com", 1500.0);
        // Баннер в 2000: до него — yandex и hotjar; GA в 3000 уже после.
        assertThat(CdpDynamicCrawler.computePreConsentHosts(tl, 2000.0, "site.ru"))
                .containsExactly("mc.yandex.ru", "hotjar.com");
    }
}
