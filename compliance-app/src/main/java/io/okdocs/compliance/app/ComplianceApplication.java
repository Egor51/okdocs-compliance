package io.okdocs.compliance.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "io.okdocs.compliance.api.web",
        "io.okdocs.compliance.api.service",
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
