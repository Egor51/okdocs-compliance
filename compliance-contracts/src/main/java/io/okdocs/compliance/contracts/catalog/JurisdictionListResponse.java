package io.okdocs.compliance.contracts.catalog;

import java.util.List;

/**
 * Ответ публичного каталога действующих юрисдикций.
 */
public record JurisdictionListResponse(List<JurisdictionDto> items) {
}
