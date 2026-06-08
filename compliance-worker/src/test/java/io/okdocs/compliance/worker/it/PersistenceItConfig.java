package io.okdocs.compliance.worker.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.okdocs.compliance.messaging.OutboxEventFactory;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import io.okdocs.compliance.worker.service.ScanLifecycleService;
import io.okdocs.compliance.worker.service.ScanProgressService;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Минимальный Spring-контекст для transactional persistence-IT: JPA-репозитории + entity persistence,
 * {@link ScanLifecycleService}/{@link ScanProgressService} и их зависимости ({@link OutboxEventFactory},
 * {@link ObjectMapper}, properties). Намеренно НЕ поднимает worker-app целиком — без Kafka/CDP/GeoIP,
 * чтобы IT были быстрыми и не требовали этих бинов. Datasource даёт {@link AbstractPostgresIT}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableJpaRepositories(basePackages = "io.okdocs.compliance.persistence")
@EntityScan(basePackages = "io.okdocs.compliance.persistence")
@EnableConfigurationProperties(ComplianceWorkerProperties.class)
@Import({ScanLifecycleService.class, ScanProgressService.class, OutboxEventFactory.class})
public class PersistenceItConfig {

    /** ObjectMapper с jsr310 — как в проде (Boot-автоконфиг); иначе Instant в payload не сериализуется. */
    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /** Ручной контроль границ транзакций (lockBatch SKIP LOCKED надо вызывать в открытой tx). */
    @Bean
    TransactionTemplate txTemplate(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }
}
