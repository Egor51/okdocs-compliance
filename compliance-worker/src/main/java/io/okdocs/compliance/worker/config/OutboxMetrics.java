package io.okdocs.compliance.worker.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.okdocs.compliance.contracts.enums.OutboxStatus;
import io.okdocs.compliance.persistence.outbox.OutboxEventRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Observability outbox: gauge'и {@code compliance.outbox.pending} и {@code compliance.outbox.dead}
 * (по {@link OutboxEventRepository#countByStatus}). DEAD > 0 — критичный алерт (см. RUNBOOK):
 * событие исчерпало ретраи и НЕ опубликовано. PENDING-рост сигналит затык relay/Kafka.
 * <p>
 * Gauge опрашивает БД при сборе метрик (scrape Prometheus), не чаще — дешёвый COUNT по индексу статуса.
 */
@Component
@RequiredArgsConstructor
public class OutboxMetrics {

    private final MeterRegistry registry;
    private final OutboxEventRepository outboxRepository;

    @PostConstruct
    void registerGauges() {
        registry.gauge("compliance.outbox.pending", this,
                m -> m.outboxRepository.countByStatus(OutboxStatus.PENDING));
        registry.gauge("compliance.outbox.dead", this,
                m -> m.outboxRepository.countByStatus(OutboxStatus.DEAD));
    }
}
