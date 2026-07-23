package io.okdocs.compliance.contracts.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/** Network implementation used by the terminal fetch attempt. */
public enum ScanFetchMode {
    HTTP,
    BROWSER,
    UNKNOWN;

    @JsonCreator
    public static ScanFetchMode fromWire(String value) {
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
