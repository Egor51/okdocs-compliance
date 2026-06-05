package io.okdocs.compliance.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "io.okdocs.compliance.worker",
        "io.okdocs.compliance.persistence",
        "io.okdocs.compliance.messaging"
})
@EnableScheduling
public class ComplianceWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ComplianceWorkerApplication.class, args);
    }
}
