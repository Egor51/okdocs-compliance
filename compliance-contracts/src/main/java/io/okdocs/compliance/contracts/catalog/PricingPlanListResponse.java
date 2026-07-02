package io.okdocs.compliance.contracts.catalog;

import java.util.List;

/** Ответ публичного каталога тарифов. */
public record PricingPlanListResponse(List<PricingPlanDto> items) {
}
