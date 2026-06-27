package io.okdocs.compliance.contracts.enums;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JurisdictionProfilesTest {

    @Test
    void singleLayerJurisdictions() {
        assertEquals(Set.of(JurisdictionLayer.RU), JurisdictionProfiles.layers(ScanJurisdiction.RU));
        assertEquals(Set.of(JurisdictionLayer.EU), JurisdictionProfiles.layers(ScanJurisdiction.EU));
    }

    @Test
    void ukDoesNotInheritEuBaseline() {
        assertEquals(Set.of(JurisdictionLayer.UK), JurisdictionProfiles.layers(ScanJurisdiction.UK));
    }

    @Test
    void deFrEsInheritEuBaselinePlusOverlay() {
        assertEquals(Set.of(JurisdictionLayer.EU, JurisdictionLayer.DE),
                JurisdictionProfiles.layers(ScanJurisdiction.DE));
        assertEquals(Set.of(JurisdictionLayer.EU, JurisdictionLayer.FR),
                JurisdictionProfiles.layers(ScanJurisdiction.FR));
        assertEquals(Set.of(JurisdictionLayer.EU, JurisdictionLayer.ES),
                JurisdictionProfiles.layers(ScanJurisdiction.ES));
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedGmHasNoProfile() {
        assertThrows(IllegalArgumentException.class,
                () -> JurisdictionProfiles.layers(ScanJurisdiction.GM));
    }
}
