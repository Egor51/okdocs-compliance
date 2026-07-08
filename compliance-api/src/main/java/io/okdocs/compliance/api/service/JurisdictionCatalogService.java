package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.contracts.catalog.JurisdictionDto;
import io.okdocs.compliance.contracts.catalog.JurisdictionListResponse;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.persistence.jurisdiction.JurisdictionCatalog;
import io.okdocs.compliance.persistence.jurisdiction.JurisdictionCatalogLaw;
import io.okdocs.compliance.persistence.jurisdiction.JurisdictionCatalogRepository;
import io.okdocs.compliance.persistence.jurisdiction.JurisdictionCatalogTranslation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Публичный каталог юрисдикций, которые реально доступны для запуска scan.
 * <p>
 * Тексты (H1/H2, SEO, название страны, список законов) хранятся в БД по locale, чтобы менять их без релиза.
 * Доступность юрисдикции определяется двумя условиями: {@code active} в таблице каталога И присутствие в
 * {@code compliance.scan.enabled-jurisdictions}. Второе — тот же список, по которому валидируется scan/checkout,
 * поэтому фронт не показывает юрисдикцию, для которой скан вернёт 400.
 */
@Service
@RequiredArgsConstructor
public class JurisdictionCatalogService {

    private static final String DEFAULT_LOCALE = "ru";
    private static final String FALLBACK_LOCALE = "en";

    private final JurisdictionCatalogRepository repository;
    private final ComplianceApiProperties properties;

    @Transactional(readOnly = true)
    public JurisdictionListResponse list(String locale) {
        String normalizedLocale = normalizeLocale(locale);
        Set<ScanJurisdiction> enabled = enabledJurisdictions();
        List<JurisdictionDto> items = repository.findByActiveTrueOrderBySortOrderAsc().stream()
                .filter(entity -> enabled.contains(entity.getCode()))
                .map(entity -> toDto(entity, normalizedLocale))
                .toList();
        return new JurisdictionListResponse(items);
    }

    @Transactional(readOnly = true)
    public Optional<JurisdictionDto> find(String rawCode, String locale) {
        Optional<ScanJurisdiction> code = parseCode(rawCode);
        if (code.isEmpty() || !enabledJurisdictions().contains(code.get())) {
            return Optional.empty();
        }

        return repository.findByCodeAndActiveTrue(code.get())
                .map(entity -> toDto(entity, normalizeLocale(locale)));
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

    private static JurisdictionDto toDto(JurisdictionCatalog entity, String locale) {
        JurisdictionCatalogTranslation t = resolveTranslation(entity, locale);
        List<String> laws = entity.getLaws().stream()
                .sorted(Comparator.comparingInt(JurisdictionCatalogLaw::getSortOrder))
                .map(JurisdictionCatalogLaw::getText)
                .toList();

        return new JurisdictionDto(
                entity.getCode(),
                entity.getSlug(),
                t.getDisplayName(),
                t.getDescription(),
                t.getSeoTitle(),
                t.getSeoDescription(),
                t.getCountryName(),
                laws,
                entity.getContentLanguage(),
                entity.isDefaultJurisdiction(),
                entity.getSortOrder()
        );
    }

    private static JurisdictionCatalogTranslation resolveTranslation(JurisdictionCatalog entity, String locale) {
        return entity.getTranslations().stream()
                .filter(translation -> translation.getLocale().equals(locale))
                .findFirst()
                .or(() -> entity.getTranslations().stream()
                        .filter(translation -> translation.getLocale().equals(FALLBACK_LOCALE))
                        .findFirst())
                .or(() -> entity.getTranslations().stream().findFirst())
                .orElseThrow(() -> new IllegalStateException(
                        "Jurisdiction " + entity.getCode() + " has no translations"));
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
}
