package io.okdocs.compliance.worker.crawler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CdpConsentTimelineTest {

    @Test
    void repeatedRequestToSameHostAfterRejectIsIncluded() {
        // Host мог встречаться до Reject; решение теперь принимается по событию, а не по first-host map.
        assertThat(CdpDynamicCrawler.isAfterRejectBoundary(11, 2_100, 10, 2_000)).isTrue();
    }

    @Test
    void requestStartedBeforeRejectButCommittedAfterIsExcluded() {
        assertThat(CdpDynamicCrawler.isAfterRejectBoundary(11, 1_900, 10, 2_000)).isFalse();
    }

    @Test
    void oldSequenceIsExcludedEvenWithLaterTimestamp() {
        assertThat(CdpDynamicCrawler.isAfterRejectBoundary(10, 2_100, 10, 2_000)).isFalse();
    }
}
