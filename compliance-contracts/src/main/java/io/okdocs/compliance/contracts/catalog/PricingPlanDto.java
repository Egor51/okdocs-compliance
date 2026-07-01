package io.okdocs.compliance.contracts.catalog;

import io.okdocs.compliance.contracts.enums.BillingPeriod;
import io.okdocs.compliance.contracts.enums.PricingPlanCode;

import java.math.BigDecimal;
import java.util.List;

/** Публичное описание тарифа/продукта для pricing UI. */
public record PricingPlanDto(
        PricingPlanCode code,
        String displayName,
        String description,
        BigDecimal priceAmount,
        String currency,
        BillingPeriod billingPeriod,
        int includedReports,
        List<String> features,
        String ctaLabel,
        boolean highlighted,
        int sortOrder
) {
}
