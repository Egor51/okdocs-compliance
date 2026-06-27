package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.contracts.catalog.JurisdictionDto;
import io.okdocs.compliance.contracts.catalog.JurisdictionListResponse;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Публичный каталог юрисдикций, которые реально доступны для запуска scan.
 * <p>
 * Источник доступности — {@code compliance.scan.enabled-jurisdictions}. Каталог не отдаёт все enum-значения:
 * deprecated/неподготовленные юрисдикции не должны попадать на фронт и создавать «пустые» проверки.
 */
@Service
@RequiredArgsConstructor
public class JurisdictionCatalogService {

    private static final String DEFAULT_LOCALE = "ru";
    private static final String FALLBACK_LOCALE = "en";
    private static final Map<ScanJurisdiction, JurisdictionMetadata> CATALOG = buildCatalog();

    private final ComplianceApiProperties properties;

    @PostConstruct
    void validateEnabledJurisdictionsHaveCatalogMetadata() {
        List<ScanJurisdiction> missing = enabledJurisdictions().stream()
                .filter(jurisdiction -> !CATALOG.containsKey(jurisdiction))
                .toList();

        if (!missing.isEmpty()) {
            throw new IllegalStateException("Enabled jurisdictions are missing catalog metadata: " + missing);
        }
    }

    public JurisdictionListResponse list(String locale) {
        String normalizedLocale = normalizeLocale(locale);
        Set<ScanJurisdiction> enabled = enabledJurisdictions();
        List<JurisdictionDto> items = CATALOG.entrySet().stream()
                .filter(entry -> enabled.contains(entry.getKey()))
                .map(entry -> toDto(entry.getKey(), entry.getValue(), normalizedLocale))
                .sorted(Comparator.comparingInt(JurisdictionDto::sortOrder)
                        .thenComparing(dto -> dto.code().name()))
                .toList();

        return new JurisdictionListResponse(items);
    }

    public Optional<JurisdictionDto> find(String rawCode, String locale) {
        Optional<ScanJurisdiction> code = parseCode(rawCode);
        if (code.isEmpty() || !enabledJurisdictions().contains(code.get())) {
            return Optional.empty();
        }

        JurisdictionMetadata metadata = CATALOG.get(code.get());
        if (metadata == null) {
            return Optional.empty();
        }

        return Optional.of(toDto(code.get(), metadata, normalizeLocale(locale)));
    }

    private Set<ScanJurisdiction> enabledJurisdictions() {
        ComplianceApiProperties.Scan scan = properties.scan();
        if (scan == null) {
            scan = new ComplianceApiProperties.Scan(null, null, null, null, null, null);
        }
        return scan.enabledJurisdictions();
    }

    private static Optional<ScanJurisdiction> parseCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(ScanJurisdiction.valueOf(rawCode.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static JurisdictionDto toDto(ScanJurisdiction code, JurisdictionMetadata metadata, String locale) {
        LocalizedJurisdiction localized = metadata.localized().getOrDefault(locale,
                metadata.localized().get(FALLBACK_LOCALE));

        return new JurisdictionDto(
                code,
                metadata.slug(),
                localized.displayName(),
                localized.description(),
                metadata.laws(),
                metadata.contentLanguage(),
                metadata.defaultJurisdiction(),
                metadata.sortOrder()
        );
    }

    private static String normalizeLocale(String rawLocale) {
        if (rawLocale == null || rawLocale.isBlank()) {
            return DEFAULT_LOCALE;
        }

        String normalized = rawLocale.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("ru")) {
            return "ru";
        }
        return FALLBACK_LOCALE;
    }

    private static Map<ScanJurisdiction, JurisdictionMetadata> buildCatalog() {
        EnumMap<ScanJurisdiction, JurisdictionMetadata> catalog = new EnumMap<>(ScanJurisdiction.class);
        catalog.put(ScanJurisdiction.RU, new JurisdictionMetadata(
                "152-fz",
                localized(
                        "Россия",
                        "Проверка сайта по требованиям 152-ФЗ и связанным требованиям обработки персональных данных.",
                        "Russia",
                        "Website compliance scan for Russian personal data requirements under 152-FZ."
                ),
                List.of("152-ФЗ"),
                "ru",
                true,
                10
        ));
        catalog.put(ScanJurisdiction.EU, new JurisdictionMetadata(
                "gdpr",
                localized(
                        "Европейский союз",
                        "Проверка по GDPR и базовым требованиям ePrivacy для сайтов, работающих с пользователями ЕС.",
                        "European Union",
                        "Compliance scan for GDPR and baseline ePrivacy requirements for websites serving EU users."
                ),
                List.of("GDPR", "ePrivacy Directive"),
                "en",
                false,
                20
        ));
        catalog.put(ScanJurisdiction.UK, new JurisdictionMetadata(
                "uk-gdpr",
                localized(
                        "Великобритания",
                        "Проверка по UK GDPR и PECR для сайтов, работающих с пользователями Великобритании.",
                        "United Kingdom",
                        "Compliance scan for UK GDPR and PECR requirements for websites serving UK users."
                ),
                List.of("UK GDPR", "PECR"),
                "en",
                false,
                30
        ));
        catalog.put(ScanJurisdiction.DE, new JurisdictionMetadata(
                "bdsg",
                localized(
                        "Германия",
                        "Проверка по GDPR с немецким overlay: BDSG и TTDSG.",
                        "Germany",
                        "Compliance scan for GDPR with Germany-specific BDSG and TTDSG overlay requirements."
                ),
                List.of("GDPR", "BDSG", "TTDSG"),
                "en",
                false,
                40
        ));
        catalog.put(ScanJurisdiction.FR, new JurisdictionMetadata(
                "cnil",
                localized(
                        "Франция",
                        "Проверка по GDPR с французским overlay и требованиями CNIL.",
                        "France",
                        "Compliance scan for GDPR with France-specific CNIL and local data protection requirements."
                ),
                List.of("GDPR", "Loi Informatique et Libertés", "CNIL guidance"),
                "en",
                false,
                50
        ));
        catalog.put(ScanJurisdiction.ES, new JurisdictionMetadata(
                "lopdgdd",
                localized(
                        "Испания",
                        "Проверка по GDPR с испанским overlay: LOPDGDD и требования AEPD.",
                        "Spain",
                        "Compliance scan for GDPR with Spain-specific LOPDGDD and AEPD requirements."
                ),
                List.of("GDPR", "LOPDGDD", "AEPD guidance"),
                "en",
                false,
                60
        ));
        return Map.copyOf(catalog);
    }

    private static Map<String, LocalizedJurisdiction> localized(String ruDisplayName,
                                                               String ruDescription,
                                                               String enDisplayName,
                                                               String enDescription) {
        return Map.of(
                "ru", new LocalizedJurisdiction(ruDisplayName, ruDescription),
                "en", new LocalizedJurisdiction(enDisplayName, enDescription)
        );
    }

    private record JurisdictionMetadata(
            String slug,
            Map<String, LocalizedJurisdiction> localized,
            List<String> laws,
            String contentLanguage,
            boolean defaultJurisdiction,
            int sortOrder
    ) {
    }

    private record LocalizedJurisdiction(String displayName, String description) {
    }
}
