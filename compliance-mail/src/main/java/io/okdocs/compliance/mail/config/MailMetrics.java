package io.okdocs.compliance.mail.config;

import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;

public class MailMetrics {
    private final MeterRegistry registry;

    public MailMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void delivery(String type, String status, Duration duration) {
        if (registry == null) return;
        registry.counter("mail.delivery", "type", type, "status", status).increment();
        registry.timer("mail.delivery.duration", "type", type).record(duration);
    }
}
