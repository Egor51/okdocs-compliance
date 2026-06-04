package io.okdocs.compliance.contracts.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Запрошен скан сайта. Авторизованный → {@code userId} заполнен; гость → {@code guestId}.
 * <p>
 * {@code tier} намеренно отсутствует: скан всегда стартует FREE, worker всегда прогоняет все
 * правила. Tier — свойство чтения отчёта, не выполнения скана.
 */
public record ScanRequestedEvent(
        UUID eventId,
        int schemaVersion,
        UUID scanId,
        Long userId,
        UUID guestId,
        String siteUrl,
        Integer maxPages,
        Instant requestedAt
) {
}
