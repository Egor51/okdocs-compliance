package io.okdocs.compliance.contracts.remediation;

import java.time.Instant;
import java.util.UUID;

/** Нейтральный ответ публичной формы без возврата персональных данных. */
public record RemediationLeadResponse(
        UUID id,
        RemediationRequestStatus status,
        Instant createdAt
) {
}
