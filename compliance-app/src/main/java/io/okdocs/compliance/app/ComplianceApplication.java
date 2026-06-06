package io.okdocs.compliance.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        // весь api: web + service + config + security + messaging + job
        // (узкий scan по web/service ронял SecurityConfig, JWT filter, properties,
        // result listener и schedulers в combined-процессе)
        "io.okdocs.compliance.api",
        "io.okdocs.compliance.worker.job",
        "io.okdocs.compliance.worker.service",
        "io.okdocs.compliance.persistence",
        "io.okdocs.compliance.messaging"
})
@EnableScheduling
public class ComplianceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ComplianceApplication.class, args);
    }
}
