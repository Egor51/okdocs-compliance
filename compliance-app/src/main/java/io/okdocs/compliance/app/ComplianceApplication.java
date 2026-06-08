package io.okdocs.compliance.app;

import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        // весь api: web + service + config + security + messaging + job
        // (узкий scan по web/service ронял SecurityConfig, JWT filter, properties,
        // result listener и schedulers в combined-процессе)
        "io.okdocs.compliance.api",
        // весь worker: job (listener/reaper) + service (pipeline/lifecycle/scoring) +
        // crawler (SiteCrawler/UrlValidator) + config (GeoIpConfig). Узкий scan ронял бы
        // crawler-бины и GeoIP в combined-процессе.
        "io.okdocs.compliance.worker.job",
        "io.okdocs.compliance.worker.service",
        "io.okdocs.compliance.worker.crawler",
        "io.okdocs.compliance.worker.config",
        "io.okdocs.compliance.persistence",
        "io.okdocs.compliance.messaging"
})
@EnableConfigurationProperties(ComplianceWorkerProperties.class)
@EnableScheduling
public class ComplianceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ComplianceApplication.class, args);
    }
}
