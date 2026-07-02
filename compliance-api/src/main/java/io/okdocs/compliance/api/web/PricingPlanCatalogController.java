package io.okdocs.compliance.api.web;

import io.okdocs.compliance.api.service.PricingPlanCatalogService;
import io.okdocs.compliance.contracts.catalog.PricingPlanDto;
import io.okdocs.compliance.contracts.catalog.PricingPlanListResponse;
import io.okdocs.compliance.contracts.enums.PricingPlanCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/** Публичный каталог тарифов для pricing UI. */
@RestController
@RequestMapping("/api/pricing/plans")
@RequiredArgsConstructor
public class PricingPlanCatalogController {

    private static final CacheControl CATALOG_CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final PricingPlanCatalogService service;

    @GetMapping
    public ResponseEntity<PricingPlanListResponse> list(
            @RequestParam(name = "locale", required = false, defaultValue = "ru") String locale) {
        return ResponseEntity.ok()
                .cacheControl(CATALOG_CACHE)
                .body(service.list(locale));
    }

    @GetMapping("/{code}")
    public ResponseEntity<PricingPlanDto> get(
            @PathVariable String code,
            @RequestParam(name = "locale", required = false, defaultValue = "ru") String locale) {
        return parseCode(code)
                .flatMap(planCode -> service.find(planCode, locale))
                .map(dto -> ResponseEntity.ok()
                        .cacheControl(CATALOG_CACHE)
                        .body(dto))
                .orElseGet(() -> ResponseEntity.notFound().<PricingPlanDto>build());
    }

    private static Optional<PricingPlanCode> parseCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(PricingPlanCode.valueOf(rawCode.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
