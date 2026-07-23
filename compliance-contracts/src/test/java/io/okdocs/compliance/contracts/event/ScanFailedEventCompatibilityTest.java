package io.okdocs.compliance.contracts.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.okdocs.compliance.contracts.enums.ScanFailureCode;
import io.okdocs.compliance.contracts.enums.ScanFailureStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScanFailedEventCompatibilityTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void readsLegacyV1WithoutStructuredFailure() throws Exception {
        ScanFailedEvent event = mapper.readValue("""
                {
                  "eventId": "ea652b15-b56b-4aef-b0a9-cb265f89e728",
                  "schemaVersion": 1,
                  "scanId": "e32d1773-c6a5-4fde-a24d-88bf6bd24c07",
                  "userId": 42,
                  "guestId": null,
                  "errorMessage": "legacy message",
                  "failedAt": "2026-07-23T12:00:00Z"
                }
                """, ScanFailedEvent.class);

        assertEquals(1, event.schemaVersion());
        assertNull(event.failure());
        assertEquals("legacy message", event.errorMessage());
    }

    @Test
    void unknownFutureEnumsDegradeSafely() throws Exception {
        ScanFailedEvent event = mapper.readValue("""
                {
                  "eventId": "ea652b15-b56b-4aef-b0a9-cb265f89e728",
                  "schemaVersion": 2,
                  "scanId": "e32d1773-c6a5-4fde-a24d-88bf6bd24c07",
                  "userId": 42,
                  "guestId": null,
                  "errorMessage": "safe fallback",
                  "failure": {
                    "code": "FUTURE_NETWORK_FAILURE",
                    "stage": "FUTURE_STAGE",
                    "retryable": false,
                    "httpStatus": null,
                    "fetchMode": null,
                    "fallbackAttempted": false,
                    "incidentId": null
                  },
                  "failedAt": "2026-07-23T12:00:00Z"
                }
                """, ScanFailedEvent.class);

        assertEquals(ScanFailureCode.UNKNOWN, event.failure().code());
        assertEquals(ScanFailureStage.UNKNOWN, event.failure().stage());
    }
}
