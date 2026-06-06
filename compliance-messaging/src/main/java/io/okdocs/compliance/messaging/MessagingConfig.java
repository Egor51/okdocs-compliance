package io.okdocs.compliance.messaging;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация модуля messaging: включает {@link OutboxProperties}.
 * Подхватывается component-scan'ом api/worker (пакет {@code io.okdocs.compliance.messaging}).
 * {@code KafkaTemplate} предоставляет Spring Kafka auto-configuration хост-приложения.
 */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class MessagingConfig {
}
