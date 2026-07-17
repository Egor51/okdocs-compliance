package io.okdocs.compliance.contracts.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockedDomainPolicyTest {

    @Test
    void blocksExactDomainAndSubdomainsWithoutMatchingLookalikeSuffixes() {
        BlockedDomainPolicy policy = new BlockedDomainPolicy(List.of("gov.ru"));

        assertTrue(policy.isBlocked("gov.ru"));
        assertTrue(policy.isBlocked("duma.gov.ru"));
        assertFalse(policy.isBlocked("notgov.ru"));
        assertFalse(policy.isBlocked("gov.ru.example.com"));
    }

    @Test
    void normalizesCaseTrailingDotAndInternationalizedDomains() {
        BlockedDomainPolicy policy = new BlockedDomainPolicy(List.of("ПРЕЗИДЕНТ.РФ."));

        assertTrue(policy.isBlocked("xn--d1abbgf6aiiy.xn--p1ai"));
        assertTrue(policy.isBlocked("www.президент.рф."));
    }

    @Test
    void rejectsUrlsAndWildcardsInConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new BlockedDomainPolicy(List.of("https://example.com")));
        assertThrows(IllegalArgumentException.class,
                () -> new BlockedDomainPolicy(List.of("*.example.com")));
    }
}
