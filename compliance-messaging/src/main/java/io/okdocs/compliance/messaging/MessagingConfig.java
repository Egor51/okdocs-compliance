package io.okdocs.compliance.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.converter.ByteArrayJsonMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;

/**
 * Конфигурация модуля messaging: включает {@link OutboxProperties}.
 * Подхватывается component-scan'ом api/worker (пакет {@code io.okdocs.compliance.messaging}).
 * {@code KafkaTemplate} предоставляет Spring Kafka auto-configuration хост-приложения.
 */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class MessagingConfig {

    /**
     * Конвертер для type-inference консьюмера ПО СИГНАТУРЕ {@code @KafkaListener} (single source of
     * truth — тип параметра метода), без type-headers от продюсера.
     * <p>
     * Продюсер шлёт payload как нетипизированный JSON ({@code OutboxPublisher} → JsonNode), type-headers
     * выключены. {@code JsonDeserializer} в таком режиме падал бы с «No type information in headers and
     * no default type provided» — он не знает целевой тип. Поэтому консьюмер читает <b>сырые байты</b>
     * ({@code ByteArrayDeserializer} в yml), а этот {@link ByteArrayJsonMessageConverter} десериализует
     * их в тип параметра listener-метода. Так api↔worker контракт работает на голом JSON.
     */
    @Bean
    RecordMessageConverter jsonRecordMessageConverter(ObjectMapper objectMapper) {
        return new ByteArrayJsonMessageConverter(objectMapper);
    }
}
