package io.okdocs.compliance.contracts.crawler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsentScenarioResultTest {

    @Test
    void notEvaluatedHasFalseAvailableAndEmptyLists() {
        ConsentScenarioResult r = ConsentScenarioResult.notEvaluated();
        assertFalse(r.available());
        assertFalse(r.banner().bannerFound());
        assertTrue(r.afterRejectCookies().isEmpty());
        assertTrue(r.afterRejectTrackerHosts().isEmpty());
        assertTrue(r.afterAcceptCookies().isEmpty());
    }

    @Test
    void nullListsBecomeEmpty() {
        ConsentScenarioResult r = new ConsentScenarioResult(
                ConsentBannerInfo.notFound(), null, null, null, true);
        assertNotNull(r.afterRejectCookies());
        assertTrue(r.afterRejectCookies().isEmpty());
        assertTrue(r.afterRejectTrackerHosts().isEmpty());
        assertTrue(r.afterAcceptCookies().isEmpty());
    }

    @Test
    void bannerNotFoundHasAllNegativeSignals() {
        ConsentBannerInfo b = ConsentBannerInfo.notFound();
        assertFalse(b.bannerFound());
        assertFalse(b.acceptButtonFound());
        assertFalse(b.rejectButtonFound());
        assertFalse(b.rejectSameLevelAsAccept());
        assertFalse(b.precheckedToggles());
    }
}
