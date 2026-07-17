package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.security.CompliancePrincipal;
import io.okdocs.compliance.contracts.enums.UserRole;
import io.okdocs.compliance.contracts.exception.ComplianceRateLimitException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryRateLimitServiceTest {

    @Test
    void guestUsesIpBucket() {
        InMemoryRateLimitService service = service(1, 10);
        CompliancePrincipal guest = CompliancePrincipal.guest(UUID.randomUUID());

        service.checkScanAllowed(guest, "203.0.113.10");

        assertThatThrownBy(() -> service.checkScanAllowed(guest, "203.0.113.10"))
                .isInstanceOf(ComplianceRateLimitException.class);
    }

    @Test
    void authAttemptsUseSeparatePerIpBucket() {
        InMemoryRateLimitService service = service(10, 10, 2);
        String ip = "203.0.113.10";

        service.checkAuthAttemptAllowed(ip);
        service.checkAuthAttemptAllowed(ip);
        assertThatThrownBy(() -> service.checkAuthAttemptAllowed(ip))
                .isInstanceOf(ComplianceRateLimitException.class);

        // Scan-бакет того же IP не задет auth-попытками (разные ключи).
        service.checkScanAllowed(CompliancePrincipal.guest(UUID.randomUUID()), ip);
    }

    @Test
    void userUsesOnlyUserBucket_notSharedIpBucket() {
        InMemoryRateLimitService service = service(1, 2);
        CompliancePrincipal guest = CompliancePrincipal.guest(UUID.randomUUID());
        CompliancePrincipal user = CompliancePrincipal.user(42L, UserRole.USER);
        String sharedIp = "203.0.113.10";

        service.checkScanAllowed(guest, sharedIp);
        assertThatThrownBy(() -> service.checkScanAllowed(CompliancePrincipal.guest(UUID.randomUUID()), sharedIp))
                .isInstanceOf(ComplianceRateLimitException.class);

        service.checkScanAllowed(user, sharedIp);
        service.checkScanAllowed(user, sharedIp);
        assertThatThrownBy(() -> service.checkScanAllowed(user, sharedIp))
                .isInstanceOf(ComplianceRateLimitException.class);
    }

    @Test
    void remediationFormUsesDedicatedPerIpBucket() {
        InMemoryRateLimitService service = service(10, 10, 30, 2);
        String ip = "203.0.113.10";

        service.checkRemediationRequestAllowed(ip);
        service.checkRemediationRequestAllowed(ip);
        assertThatThrownBy(() -> service.checkRemediationRequestAllowed(ip))
                .isInstanceOf(ComplianceRateLimitException.class);

        // Лид-форма не расходует scan bucket того же посетителя.
        service.checkScanAllowed(CompliancePrincipal.guest(UUID.randomUUID()), ip);
    }

    private static InMemoryRateLimitService service(int guestPerIp, int userPerHour) {
        return service(guestPerIp, userPerHour, 30);
    }

    private static InMemoryRateLimitService service(int guestPerIp, int userPerHour, int authPerIp) {
        return service(guestPerIp, userPerHour, authPerIp, 5);
    }

    private static InMemoryRateLimitService service(int guestPerIp, int userPerHour,
                                                    int authPerIp, int remediationPerIp) {
        var props = new ComplianceApiProperties(
                null,
                new ComplianceApiProperties.RateLimit(
                        guestPerIp, userPerHour, authPerIp, remediationPerIp),
                null, null, null, null, null, null, null);
        return new InMemoryRateLimitService(props);
    }
}
