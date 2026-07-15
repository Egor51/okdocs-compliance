package io.okdocs.compliance.contracts.remediation;

import java.time.Instant;
import java.util.UUID;

public record RemediationRequestResponse(
        UUID id,
        UUID reportId,
        String siteUrl,
        String email,
        RemediationRequestStatus status,
        Instant createdAt
) {
}
