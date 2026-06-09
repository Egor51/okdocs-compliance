package io.okdocs.compliance.persistence;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Регистрирует JPA-сущности и Spring Data репозитории этого модуля
 * ({@code io.okdocs.compliance.persistence.*}).
 * <p>
 * Boot-автоконфигурация {@code @EnableJpaRepositories}/{@code @EntityScan} по умолчанию смотрит в
 * пакет главного класса приложения. Но все три launcher'а живут в других пакетах
 * ({@code io.okdocs.compliance.api|worker|app}) — без явного скана сущности/репозитории persistence
 * не регистрируются, и контекст падает с {@code NoSuchBeanDefinitionException} на первом репозитории.
 * <p>
 * Конфиг лежит в самом persistence и подхватывается любым процессом, который component-сканирует
 * {@code io.okdocs.compliance.persistence} (standalone api, standalone worker, combined app) — одна
 * точка вместо дублирования аннотаций на каждом launcher'е.
 */
@Configuration
@EnableJpaRepositories(basePackages = "io.okdocs.compliance.persistence")
@EntityScan(basePackages = "io.okdocs.compliance.persistence")
public class PersistenceConfig {
}
