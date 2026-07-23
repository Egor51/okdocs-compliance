package io.okdocs.compliance.contracts.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/** Stable, user-safe machine code for a terminal scan failure. */
public enum ScanFailureCode {
    INVALID_URL,
    UNSAFE_TARGET,
    DNS_NOT_FOUND,
    DNS_TIMEOUT,
    CONNECT_FAILED,
    CONNECT_TIMEOUT,
    TLS_CERT_INVALID,
    TLS_HOSTNAME_MISMATCH,
    TLS_HANDSHAKE_FAILED,
    TLS_HANDSHAKE_TIMEOUT,
    ROBOTS_DENIED,
    HTTP_UNAUTHORIZED,
    HTTP_FORBIDDEN,
    HTTP_NOT_FOUND,
    HTTP_RATE_LIMITED,
    HTTP_CLIENT_ERROR,
    HTTP_SERVER_ERROR,
    RESPONSE_TIMEOUT,
    HTTP_INVALID_RESPONSE,
    RESPONSE_TOO_LARGE,
    REDIRECT_LOOP,
    BROWSER_UNAVAILABLE,
    BROWSER_NAVIGATION_TIMEOUT,
    PIPELINE_TIMEOUT,
    PARSING_FAILED,
    ANALYSIS_FAILED,
    INTERNAL_ERROR,
    UNKNOWN;

    /** Unknown future wire values degrade safely instead of breaking an older consumer. */
    @JsonCreator
    public static ScanFailureCode fromWire(String value) {
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
