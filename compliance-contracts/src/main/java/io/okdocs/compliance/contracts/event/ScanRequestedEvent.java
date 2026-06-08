package io.okdocs.compliance.contracts.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Запрошен скан сайта. Авторизованный → {@code userId} заполнен; гость → {@code guestId}.
 * <p>
 * Событие — это команда «обработай {@code scanId}». Режим выполнения ({@code kind}, {@code maxPages},
 * {@code dynamicRequired}, {@code tier}) намеренно <b>не</b> передаётся: worker читает его из строки
 * {@code ComplianceScan} в БД (единый source of truth), не из producer-решений. {@code siteUrl} и
 * {@code userId}/{@code guestId} оставлены для логирования/диагностики на стороне consumer'а.
 */
public record ScanRequestedEvent(
        UUID eventId,
        int schemaVersion,
        UUID scanId,
        Long userId,
        UUID guestId,
        String siteUrl,
        Instant requestedAt
) {
}
