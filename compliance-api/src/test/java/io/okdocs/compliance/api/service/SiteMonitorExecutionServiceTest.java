package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.enums.UserPlan;
import io.okdocs.compliance.persistence.auth.AppUser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class SiteMonitorExecutionServiceTest {

    @Test
    void nextScheduledStaysOnCadenceAndSkipsMissedIntervals() {
        Instant scheduled = Instant.parse("2026-01-01T09:00:00Z");
        Instant now = Instant.parse("2026-01-08T10:00:00Z");

        Instant next = SiteMonitorExecutionService.nextScheduled(
                scheduled, 3, ZoneId.of("UTC"), now);

        assertThat(next).isEqualTo(Instant.parse("2026-01-10T09:00:00Z"));
    }

    @Test
    void nextScheduledPreservesLocalTimeAcrossDst() {
        Instant scheduled = Instant.parse("2026-03-27T08:00:00Z"); // 09:00 Europe/Berlin

        Instant next = SiteMonitorExecutionService.nextScheduled(
                scheduled, 3, ZoneId.of("Europe/Berlin"), scheduled);

        assertThat(next).isEqualTo(Instant.parse("2026-03-30T07:00:00Z")); // still 09:00 local
    }

    @Test
    void paidPlanMustHaveFutureExpiry() {
        AppUser user = new AppUser();
        user.setPlan(UserPlan.PRO);
        user.setPlanRenewsAt(Instant.parse("2026-08-01T00:00:00Z"));

        assertThat(SiteMonitorExecutionService.hasActivePaidPlan(
                user, Instant.parse("2026-07-16T00:00:00Z"))).isTrue();
        assertThat(SiteMonitorExecutionService.hasActivePaidPlan(
                user, Instant.parse("2026-08-01T00:00:00Z"))).isFalse();
    }
}
