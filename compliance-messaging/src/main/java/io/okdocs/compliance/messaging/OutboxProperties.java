package io.okdocs.compliance.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки outbox relay (общие для api и worker, prefix {@code compliance.outbox}).
 *
 * @param maxRetries     после исчерпания событие → {@code DEAD}
 * @param batchSize      сколько событий захватывать за один tick
 * @param backoffBase    база экспоненциального backoff между ретраями публикации
 * @param backoffMax     потолок backoff
 */
@ConfigurationProperties(prefix = "compliance.outbox")
public record OutboxProperties(
        Integer maxRetries,
        Integer batchSize,
        Duration backoffBase,
        Duration backoffMax
) {

    public OutboxProperties {
        if (maxRetries == null) {
            maxRetries = 5;
        }
        if (batchSize == null) {
            batchSize = 100;
        }
        if (backoffBase == null) {
            backoffBase = Duration.ofSeconds(10);
        }
        if (backoffMax == null) {
            backoffMax = Duration.ofMinutes(10);
        }
    }
}
