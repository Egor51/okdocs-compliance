package io.okdocs.compliance.api.web;

import io.okdocs.compliance.api.service.JurisdictionCatalogService;
import io.okdocs.compliance.contracts.catalog.JurisdictionDto;
import io.okdocs.compliance.contracts.catalog.JurisdictionListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Публичный каталог юрисдикций для фронта.
 */
@RestController
@RequestMapping("/api/jurisdictions")
@RequiredArgsConstructor
public class JurisdictionCatalogController {

    private static final CacheControl CATALOG_CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final JurisdictionCatalogService service;

    @GetMapping
    public ResponseEntity<JurisdictionListResponse> list(
            @RequestParam(name = "locale", required = false, defaultValue = "ru") String locale) {
        return ResponseEntity.ok()
                .cacheControl(CATALOG_CACHE)
                .body(service.list(locale));
    }

    @GetMapping("/{code}")
    public ResponseEntity<JurisdictionDto> get(
            @PathVariable String code,
            @RequestParam(name = "locale", required = false, defaultValue = "ru") String locale) {
        return service.find(code, locale)
                .map(dto -> ResponseEntity.ok()
                        .cacheControl(CATALOG_CACHE)
                        .body(dto))
                .orElseGet(() -> ResponseEntity.notFound().<JurisdictionDto>build());
    }
}
