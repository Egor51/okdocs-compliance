package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.contracts.catalog.JurisdictionDto;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JurisdictionCatalogServiceTest {

    @Test
    void listReturnsOnlyEnabledJurisdictionsSortedAndLocalized() {
        JurisdictionCatalogService service = service(Set.of(ScanJurisdiction.DE, ScanJurisdiction.RU));

        var response = service.list("ru-RU");

        assertThat(response.items())
                .extracting(JurisdictionDto::code)
                .containsExactly(ScanJurisdiction.RU, ScanJurisdiction.DE);
        assertThat(response.items().getFirst().displayName()).isEqualTo("Россия");
        assertThat(response.items().getFirst().defaultJurisdiction()).isTrue();
        assertThat(response.items().get(1).displayName()).isEqualTo("Германия");
    }

    @Test
    void findReturnsSingleEnabledJurisdictionByCaseInsensitiveCode() {
        JurisdictionCatalogService service = service(Set.of(ScanJurisdiction.DE));

        var result = service.find("de", "en");

        assertThat(result).isPresent();
        assertThat(result.get().code()).isEqualTo(ScanJurisdiction.DE);
        assertThat(result.get().displayName()).isEqualTo("Germany");
        assertThat(result.get().slug()).isEqualTo("bdsg");
    }

    @Test
    void findReturnsEmptyForDisabledOrUnknownJurisdiction() {
        JurisdictionCatalogService service = service(Set.of(ScanJurisdiction.RU));

        assertThat(service.find("DE", "ru")).isEmpty();
        assertThat(service.find("ATLANTIS", "ru")).isEmpty();
    }

    @Test
    void validateFailsWhenEnabledJurisdictionHasNoCatalogMetadata() {
        JurisdictionCatalogService service = service(Set.of(ScanJurisdiction.GM));

        assertThatThrownBy(service::validateEnabledJurisdictionsHaveCatalogMetadata)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GM");
    }

    private static JurisdictionCatalogService service(Set<ScanJurisdiction> enabled) {
        var props = new ComplianceApiProperties(
                null,
                null,
                new ComplianceApiProperties.Scan(null, null, null, null, null, enabled),
                null,
                null,
                null,
                null,
                null,
                null);
        return new JurisdictionCatalogService(props);
    }
}
