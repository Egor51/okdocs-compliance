package io.okdocs.compliance.contracts.crawler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(ConsentScenarioFailureReason.CDP_ERROR, r.failureReason());
        assertFalse(r.rejectClicked());
        assertFalse(r.postRejectSnapshotAvailable());
    }

    @Test
    void explicitFailurePreservesScenarioProgressAndReason() {
        ConsentBannerInfo banner = new ConsentBannerInfo(
                true, true, true, false, false, true, false, "CMP");
        ConsentScenarioResult r = ConsentScenarioResult.failed(
                banner, true, true, false, ConsentScenarioFailureReason.REJECT_CLICK_FAILED);

        assertTrue(r.inspectionCompleted());
        assertTrue(r.rejectFound());
        assertFalse(r.rejectClicked());
        assertFalse(r.available());
        assertEquals(ConsentScenarioFailureReason.REJECT_CLICK_FAILED, r.failureReason());
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
