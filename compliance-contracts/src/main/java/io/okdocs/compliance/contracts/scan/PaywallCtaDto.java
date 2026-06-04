package io.okdocs.compliance.contracts.scan;

/** Призыв к покупке PREMIUM. Присутствует только в FREE-отчёте. */
public record PaywallCtaDto(
        String title,
        String text,
        String actionUrl
) {
}
