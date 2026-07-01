package io.okdocs.compliance.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Регистрирует {@link ComplianceApiProperties}. Лежит в сканируемом пакете
 * {@code io.okdocs.compliance.api}, поэтому подхватывается и отдельным ComplianceApiApplication,
 * и объединённым ComplianceApplication — без правки boot-классов.
 */
@Configuration
@EnableConfigurationProperties({ComplianceApiProperties.class, YooKassaProperties.class})
public class ApiPropertiesConfig {
}
