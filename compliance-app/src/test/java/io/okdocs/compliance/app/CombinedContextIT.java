package io.okdocs.compliance.app;

import io.okdocs.compliance.api.service.ScanCommandService;
import io.okdocs.compliance.api.service.RateLimitService;
import io.okdocs.compliance.api.web.ComplianceScanController;
import io.okdocs.compliance.messaging.OutboxPublisher;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import io.okdocs.compliance.persistence.billing.ScanBalance;
import io.okdocs.compliance.persistence.billing.ScanBalanceRepository;
import io.okdocs.compliance.worker.crawler.CdpAvailabilityChecker;
import io.okdocs.compliance.worker.crawler.DynamicCrawler;
import io.okdocs.compliance.worker.crawler.NoopDynamicCrawler;
import io.okdocs.compliance.worker.job.ScanReaper;
import io.okdocs.compliance.worker.job.ScanRequestedListener;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Главная проверка Этапа 6: реальный {@link ComplianceApplication} (combined api+worker) поднимает
 * <b>единый</b> Spring-контекст на настоящем PostgreSQL (Testcontainers) и {@code @EmbeddedKafka},
 * а объединённый {@code application.yml} удовлетворяет и api-, и worker-, и messaging-биндинги.
 * <p>
 * Доказывает то, что нельзя поймать компиляцией: api- и worker-бины сосуществуют в одном контексте
 * (раньше узкий component-scan ронял SecurityConfig/листенеры/crawler — см. {@code ComplianceApplication}),
 * а отсутствие {@code compliance.auth.jwt-secret} в combined-yml ронял бы старт. Профиль {@code local}:
 * InMemory rate-limiter вместо Redis + premium CDP выключен (application-local.yml) — инфра не нужна.
 * <p>
 * {@code @Tag("integration")} + имя {@code *IT} → failsafe в фазе {@code verify}. Требует Docker.
 */
@Tag("integration")
@SpringBootTest(classes = ComplianceApplication.class)
@ActiveProfiles("local")
@EmbeddedKafka(partitions = 1,
        topics = {"compliance.scan.requested", "compliance.scan.completed", "compliance.scan.failed"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class CombinedContextIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // spring.kafka.bootstrap-servers подставляет @EmbeddedKafka (bootstrapServersProperty).
    }

    @Autowired
    ApplicationContext ctx;
    @Autowired
    AppUserRepository userRepository;
    @Autowired
    ScanBalanceRepository balanceRepository;
    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void apiAndWorkerBeansCoexistInOneContext() {
        // api-часть (web + service): узкий scan раньше ронял эти бины в combined
        assertThat(ctx.getBeansOfType(ComplianceScanController.class)).isNotEmpty();
        assertThat(ctx.getBean(ScanCommandService.class)).isNotNull();

        // worker-часть (job + crawler)
        assertThat(ctx.getBean(ScanRequestedListener.class)).isNotNull();
        assertThat(ctx.getBean(ScanReaper.class)).isNotNull();

        // shared messaging: ровно один OutboxPublisher на процесс (§6) обслуживает обе половины
        assertThat(ctx.getBeansOfType(OutboxPublisher.class)).hasSize(1);
    }

    @Test
    void localProfile_usesInMemoryRateLimiterAndDisabledPremiumCdp() {
        // local: InMemoryRateLimitService (@Profile("local")), Redis не требуется
        RateLimitService rateLimit = ctx.getBean(RateLimitService.class);
        assertThat(rateLimit.getClass().getSimpleName()).isEqualTo("InMemoryRateLimitService");

        // dynamic выключен → бин-заглушка NoopDynamicCrawler; CdpAvailabilityChecker присутствует,
        // но при premium-enabled=false (application-local.yml) не роняет старт.
        assertThat(ctx.getBean(DynamicCrawler.class)).isInstanceOf(NoopDynamicCrawler.class);
        assertThat(ctx.getBean(CdpAvailabilityChecker.class)).isNotNull();
    }

    @Test
    void devSeedMigration_createsUserWithPassAndBalance10() {
        // V900__dev_seed_user.sql (db/migration-dev, добавлен в flyway.locations только у combined-app)
        AppUser user = userRepository.findByEmailIgnoreCase("user@local").orElseThrow();
        // Пароль 'pass' реально матчится тем же BCryptPasswordEncoder, что и логин (AuthService)
        assertThat(passwordEncoder.matches("pass", user.getPasswordHash())).isTrue();

        ScanBalance balance = balanceRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(balance.available()).isEqualTo(10);
    }
}
