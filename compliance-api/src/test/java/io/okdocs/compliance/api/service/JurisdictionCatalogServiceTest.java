package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.contracts.catalog.JurisdictionDto;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.persistence.jurisdiction.JurisdictionCatalog;
import io.okdocs.compliance.persistence.jurisdiction.JurisdictionCatalogLaw;
import io.okdocs.compliance.persistence.jurisdiction.JurisdictionCatalogRepository;
import io.okdocs.compliance.persistence.jurisdiction.JurisdictionCatalogTranslation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JurisdictionCatalogServiceTest {

    private static final JurisdictionCatalog RU = jurisdiction(ScanJurisdiction.RU, "152-fz", "ru", true, 10,
            "Россия", "Найдите нарушения 152-ФЗ", "Россия ru", "Россия seo desc", "Российская Федерация",
            "Russia", "Find 152-FZ violations", "Russia en", "Russia seo desc", "Russian Federation",
            List.of("152-ФЗ"));
    private static final JurisdictionCatalog DE = jurisdiction(ScanJurisdiction.DE, "bdsg", "en", false, 40,
            "Германия", "Проверка по GDPR", "Германия ru", "Германия seo desc", "Германия",
            "Germany", "GDPR with BDSG", "Germany en", "Germany seo desc", "Germany",
            List.of("GDPR", "BDSG", "TTDSG"));

    @Test
    void listReturnsOnlyEnabledJurisdictionsSortedAndLocalized() {
        JurisdictionCatalogRepository repository = mock(JurisdictionCatalogRepository.class);
        // репозиторий уже отдаёт active + сортировку по sort_order; сервис лишь пересекает с enabled
        when(repository.findByActiveTrueOrderBySortOrderAsc()).thenReturn(List.of(RU, DE));
        JurisdictionCatalogService service = service(repository, Set.of(ScanJurisdiction.DE, ScanJurisdiction.RU));

        var response = service.list("ru-RU");

        assertThat(response.items())
                .extracting(JurisdictionDto::code)
                .containsExactly(ScanJurisdiction.RU, ScanJurisdiction.DE);
        JurisdictionDto ru = response.items().getFirst();
        assertThat(ru.displayName()).isEqualTo("Россия");
        assertThat(ru.seoTitle()).isEqualTo("Россия ru");
        assertThat(ru.seoDescription()).isEqualTo("Россия seo desc");
        assertThat(ru.countryName()).isEqualTo("Российская Федерация");
        assertThat(ru.defaultJurisdiction()).isTrue();
        assertThat(response.items().get(1).displayName()).isEqualTo("Германия");
    }

    @Test
    void listFiltersOutJurisdictionsNotInEnabledSet() {
        JurisdictionCatalogRepository repository = mock(JurisdictionCatalogRepository.class);
        when(repository.findByActiveTrueOrderBySortOrderAsc()).thenReturn(List.of(RU, DE));
        JurisdictionCatalogService service = service(repository, Set.of(ScanJurisdiction.RU));

        var response = service.list("ru");

        assertThat(response.items())
                .extracting(JurisdictionDto::code)
                .containsExactly(ScanJurisdiction.RU);
    }

    @Test
    void findReturnsSingleEnabledJurisdictionByCaseInsensitiveCodeWithFallbackLocale() {
        JurisdictionCatalogRepository repository = mock(JurisdictionCatalogRepository.class);
        when(repository.findByCodeAndActiveTrue(ScanJurisdiction.DE)).thenReturn(Optional.of(DE));
        JurisdictionCatalogService service = service(repository, Set.of(ScanJurisdiction.DE));

        var result = service.find("de", "en");

        assertThat(result).isPresent();
        assertThat(result.get().code()).isEqualTo(ScanJurisdiction.DE);
        assertThat(result.get().displayName()).isEqualTo("Germany");
        assertThat(result.get().slug()).isEqualTo("bdsg");
        assertThat(result.get().laws()).containsExactly("GDPR", "BDSG", "TTDSG");
    }

    @Test
    void findReturnsEmptyForDisabledOrUnknownJurisdictionWithoutHittingRepository() {
        JurisdictionCatalogRepository repository = mock(JurisdictionCatalogRepository.class);
        // DE не в enabled → сервис не должен обращаться к репозиторию за ним
        JurisdictionCatalogService service = service(repository, Set.of(ScanJurisdiction.RU));

        assertThat(service.find("DE", "ru")).isEmpty();
        assertThat(service.find("ATLANTIS", "ru")).isEmpty();
    }

    private static JurisdictionCatalogService service(JurisdictionCatalogRepository repository,
                                                      Set<ScanJurisdiction> enabled) {
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
        return new JurisdictionCatalogService(repository, props);
    }

    private static JurisdictionCatalog jurisdiction(ScanJurisdiction code, String slug, String contentLanguage,
                                                    boolean defaultJurisdiction, int sortOrder,
                                                    String ruName, String ruDesc, String ruSeoTitle,
                                                    String ruSeoDesc, String ruCountry,
                                                    String enName, String enDesc, String enSeoTitle,
                                                    String enSeoDesc, String enCountry,
                                                    List<String> laws) {
        JurisdictionCatalog entity = new JurisdictionCatalog();
        entity.setCode(code);
        entity.setSlug(slug);
        entity.setActive(true);
        entity.setContentLanguage(contentLanguage);
        entity.setDefaultJurisdiction(defaultJurisdiction);
        entity.setSortOrder(sortOrder);
        entity.setTranslations(new ArrayList<>(List.of(
                translation(entity, "ru", ruName, ruDesc, ruSeoTitle, ruSeoDesc, ruCountry),
                translation(entity, "en", enName, enDesc, enSeoTitle, enSeoDesc, enCountry))));

        List<JurisdictionCatalogLaw> lawEntities = new ArrayList<>();
        int order = 10;
        for (String law : laws) {
            JurisdictionCatalogLaw l = new JurisdictionCatalogLaw();
            l.setJurisdiction(entity);
            l.setText(law);
            l.setSortOrder(order);
            order += 10;
            lawEntities.add(l);
        }
        // сервис сортирует сам — специально кладём в обратном порядке
        lawEntities.sort(Comparator.comparingInt(JurisdictionCatalogLaw::getSortOrder).reversed());
        entity.setLaws(lawEntities);
        return entity;
    }

    private static JurisdictionCatalogTranslation translation(JurisdictionCatalog entity, String locale,
                                                              String displayName, String description,
                                                              String seoTitle, String seoDescription,
                                                              String countryName) {
        JurisdictionCatalogTranslation t = new JurisdictionCatalogTranslation();
        t.setJurisdiction(entity);
        t.setLocale(locale);
        t.setDisplayName(displayName);
        t.setDescription(description);
        t.setSeoTitle(seoTitle);
        t.setSeoDescription(seoDescription);
        t.setCountryName(countryName);
        return t;
    }
}
