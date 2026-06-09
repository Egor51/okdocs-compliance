package io.okdocs.compliance.worker.crawler;

import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health-индикатор платного premium-потока (CDP headless Chromium), §5.4. Чтобы
 * Kubernetes/monitoring видел проблему до первого платного скана, а не по росту FAILED+refund.
 * <ul>
 *   <li>{@code UP} — premium выключен ({@code premium-enabled=false}): CDP не нужен;</li>
 *   <li>{@code UP} — premium включён и CDP доступен;</li>
 *   <li>{@code DOWN} — premium включён, но CDP недоступен.</li>
 * </ul>
 * Endpoint регистрируется под именем {@code cdp} в {@code /actuator/health}.
 */
@Component("cdp")
public class CdpHealthIndicator implements HealthIndicator {

    private final ComplianceWorkerProperties properties;
    private final DynamicCrawler dynamicCrawler;

    public CdpHealthIndicator(ComplianceWorkerProperties properties, DynamicCrawler dynamicCrawler) {
        this.properties = properties;
        this.dynamicCrawler = dynamicCrawler;
    }

    @Override
    public Health health() {
        var dyn = properties.getCrawler().getDynamic();
        if (!dyn.isPremiumEnabled()) {
            return Health.up().withDetail("premiumEnabled", false).build();
        }
        boolean available = dynamicCrawler.isAvailable();
        Health.Builder builder = available ? Health.up() : Health.down();
        return builder
                .withDetail("premiumEnabled", true)
                .withDetail("cdpAvailable", available)
                .build();
    }
}
