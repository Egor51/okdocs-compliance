package io.okdocs.compliance.contracts.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** Контракт на будущее: запланировать скан. Endpoint пока не реализуется. */
public record ScheduleScanRequest(
        @NotBlank String siteUrl,
        @NotNull Instant scheduledAt
) {
}
