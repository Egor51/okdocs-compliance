package io.okdocs.compliance.worker.it;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * База для persistence/transactional integration-тестов на реальном PostgreSQL (Testcontainers).
 * <p>
 * Один контейнер на весь прогон (static + ручной start, без {@code @Container} lifecycle, чтобы не
 * тянуть модуль testcontainers-junit-jupiter). Flyway применяет миграции из {@code db/migration}
 * (включая native {@code FOR UPDATE SKIP LOCKED} в outbox-выборке, которую H2 не воспроизводит —
 * отсюда настоящий PG, а не in-memory).
 * <p>
 * Требует запущенного Docker. Помечено {@code @Tag("integration")}; запускается failsafe в фазе
 * {@code verify}.
 */
@Tag("integration")
public abstract class AbstractPostgresIT {

    // Без принудительного UTC у контейнера: запросы должны быть TZ-safe сами по себе
    // (now() AT TIME ZONE 'UTC' в claimBatch). Тест под дефолтной TZ — регрессионный сторож этого.
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Flyway применяет миграции; Hibernate только валидирует схему.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
