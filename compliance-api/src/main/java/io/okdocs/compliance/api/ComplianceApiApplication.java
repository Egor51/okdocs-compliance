package io.okdocs.compliance.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "io.okdocs.compliance.api",
        "io.okdocs.compliance.persistence",
        "io.okdocs.compliance.messaging"
})
@EnableScheduling
public class ComplianceApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ComplianceApiApplication.class, args);
    }
}
