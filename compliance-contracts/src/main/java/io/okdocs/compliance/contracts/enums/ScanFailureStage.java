package io.okdocs.compliance.contracts.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/** Pipeline boundary at which a terminal failure was observed. */
public enum ScanFailureStage {
    VALIDATION,
    DNS,
    CONNECT,
    TLS,
    ROBOTS,
    HTTP_FETCH,
    BROWSER_FETCH,
    PARSING,
    ANALYSIS,
    PIPELINE,
    UNKNOWN;

    @JsonCreator
    public static ScanFailureStage fromWire(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
