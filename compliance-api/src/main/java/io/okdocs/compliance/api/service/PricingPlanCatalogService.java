package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.catalog.PricingPlanDto;
import io.okdocs.compliance.contracts.catalog.PricingPlanListResponse;
import io.okdocs.compliance.contracts.enums.PricingPlanCode;
import io.okdocs.compliance.persistence.billing.PricingPlan;
import io.okdocs.compliance.persistence.billing.PricingPlanFeature;
import io.okdocs.compliance.persistence.billing.PricingPlanRepository;
import io.okdocs.compliance.persistence.billing.PricingPlanTranslation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Read-only каталог pricing-тарифов для UI. */
@Service
@RequiredArgsConstructor
public class PricingPlanCatalogService {

    private static final String DEFAULT_LOCALE = "ru";
    private static final String FALLBACK_LOCALE = "en";

    private final PricingPlanRepository repository;

    @Transactional(readOnly = true)
    public PricingPlanListResponse list(String locale) {
        String normalizedLocale = normalizeLocale(locale);
        List<PricingPlanDto> items = repository.findByActiveTrueOrderBySortOrderAsc().stream()
                .map(plan -> toDto(plan, normalizedLocale))
                .toList();
        return new PricingPlanListResponse(items);
    }

    @Transactional(readOnly = true)
    public Optional<PricingPlanDto> find(PricingPlanCode code, String locale) {
        if (code == null) {
            return Optional.empty();
        }
        String normalizedLocale = normalizeLocale(locale);
        return repository.findByCodeAndActiveTrue(code).map(plan -> toDto(plan, normalizedLocale));
    }

    private static PricingPlanDto toDto(PricingPlan plan, String locale) {
        PricingPlanTranslation translation = resolveTranslation(plan, locale);
        List<String> features = translation.getFeatures().stream()
                .sorted(Comparator.comparingInt(PricingPlanFeature::getSortOrder))
                .map(PricingPlanFeature::getText)
                .toList();

        return new PricingPlanDto(
                plan.getCode(),
                translation.getDisplayName(),
                translation.getDescription(),
                translation.getPriceAmount(),
                translation.getCurrency(),
                plan.getBillingPeriod(),
                plan.getIncludedReports(),
                features,
                translation.getCtaLabel(),
                plan.isHighlighted(),
                plan.getSortOrder()
        );
    }

    /** Нормализует locale так же, как каталог (ru по умолчанию; срезает регион). Для повторного использования. */
    public String normalizeLocalePublic(String rawLocale) {
        return normalizeLocale(rawLocale);
    }

    /**
     * Резолв продукта и локализованной цены для payment-flow одним read-транзакционным вызовом:
     * возвращает value object, а НЕ JPA-entity, чтобы не зависеть от open-in-view (выключен) и не ловить
     * {@code LazyInitializationException} на {@code plan.getTranslations()} вне транзакции.
     * Тот же fallback locale → en → любой, что и в каталоге.
     *
     * @return {@code empty}, если активного продукта с таким code нет
     */
    @Transactional(readOnly = true)
    public Optional<TopUpPricing> resolveTopUpPricing(PricingPlanCode code, String locale) {
        if (code == null) {
            return Optional.empty();
        }
        String normalizedLocale = normalizeLocale(locale);
        return repository.findByCodeAndActiveTrue(code).map(plan -> {
            PricingPlanTranslation t = resolveTranslation(plan, normalizedLocale);
            return new TopUpPricing(
                    plan.getCode(),
                    plan.getBillingPeriod(),
                    plan.getIncludedReports(),
                    t.getPriceAmount(),
                    t.getCurrency(),
                    t.getDisplayName());
        });
    }

    /** Снимок продукта/цены для payment-flow (detached value object, безопасен вне транзакции). */
    public record TopUpPricing(
            PricingPlanCode code,
            io.okdocs.compliance.contracts.enums.BillingPeriod billingPeriod,
            int includedReports,
            java.math.BigDecimal priceAmount,
            String currency,
            String displayName) {
    }

    private static PricingPlanTranslation resolveTranslation(PricingPlan plan, String locale) {
        return plan.getTranslations().stream()
                .filter(translation -> translation.getLocale().equals(locale))
                .findFirst()
                .or(() -> plan.getTranslations().stream()
                        .filter(translation -> translation.getLocale().equals(FALLBACK_LOCALE))
                        .findFirst())
                .or(() -> plan.getTranslations().stream().findFirst())
                .orElseThrow(() -> new IllegalStateException(
                        "Pricing plan " + plan.getCode() + " has no translations"));
    }

    private static String normalizeLocale(String rawLocale) {
        if (rawLocale == null || rawLocale.isBlank()) {
            return DEFAULT_LOCALE;
        }

        String normalized = rawLocale.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf('-');
        if (separator > 0) {
            normalized = normalized.substring(0, separator);
        }
        separator = normalized.indexOf('_');
        if (separator > 0) {
            normalized = normalized.substring(0, separator);
        }
        return normalized.isBlank() ? DEFAULT_LOCALE : normalized;
    }
}
