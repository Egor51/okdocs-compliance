package io.okdocs.compliance.contracts.crawler;

/**
 * Безопасное наблюдение сетевого запроса consent-сценария. URL, query string, headers и значения
 * cookies намеренно не сохраняются: для доказательства достаточно времени, хоста и типа ресурса.
 */
public record NetworkRequestObservation(
        long sequence,
        double epochMs,
        String host,
        String resourceType
) {
}
