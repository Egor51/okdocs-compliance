package io.okdocs.compliance.contracts.monitoring;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateSiteMonitorRequest(
        @NotBlank @Size(max = 2048) String siteUrl,
        @NotBlank @Size(max = 30) String jurisdiction,
        @Min(2) @Max(3) int intervalDays,
        @NotBlank @Size(max = 64) String timezone,
        @NotBlank @Size(max = 16) String locale,
        UUID baselineScanId,
        @NotNull Boolean notificationsEnabled
) {
}
