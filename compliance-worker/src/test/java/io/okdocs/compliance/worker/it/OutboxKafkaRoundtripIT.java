package io.okdocs.compliance.worker.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.okdocs.compliance.contracts.event.ScanRequestedEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Доказывает главный недоказанный риск Этапа 5: payload публикуется как нетипизированный
 * {@link JsonNode} (как в {@code OutboxPublisher.publishOne}), а {@code @KafkaListener} принимает
 * <b>typed</b> {@link ScanRequestedEvent} — при {@code spring.json.*.type.headers=false}. Если
 * type-inference по сигнатуре метода сломается, тест упадёт. Реальный брокер — {@code @EmbeddedKafka}.
 */
@Tag("integration")
@SpringBootTest(classes = OutboxKafkaRoundtripIT.KafkaItConfig.class)
@ActiveProfiles("kafka-it")
@EmbeddedKafka(partitions = 1, topics = OutboxKafkaRoundtripIT.TOPIC)
class OutboxKafkaRoundtripIT {

    static final String TOPIC = "compliance.scan.requested.it";

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    TestConsumer consumer;

    @Test
    void jsonNodePayload_isReceivedAsTypedRecord() throws Exception {
        UUID scanId = UUID.randomUUID();
        ScanRequestedEvent original = new ScanRequestedEvent(
                UUID.randomUUID(), 1, scanId, 42L, null, "https://example.com", Instant.now());

        // Точная имитация OutboxPublisher: сериализуем событие в строку, читаем обратно как JsonNode
        // и шлём именно JsonNode (а не typed record) — продюсер не пишет type-headers.
        JsonNode payload = objectMapper.readValue(objectMapper.writeValueAsString(original), JsonNode.class);
        kafkaTemplate.send(TOPIC, scanId.toString(), payload).get(10, TimeUnit.SECONDS);

        ScanRequestedEvent received = consumer.poll(10);
        assertThat(received).isNotNull();
        assertThat(received.scanId()).isEqualTo(scanId);
        assertThat(received.userId()).isEqualTo(42L);
        assertThat(received.siteUrl()).isEqualTo("https://example.com");
        assertThat(received.eventId()).isEqualTo(original.eventId());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class})
    @EnableKafka
    @org.springframework.context.annotation.Import(
            io.okdocs.compliance.messaging.MessagingConfig.class) // даёт RecordMessageConverter
    static class KafkaItConfig {

        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }

        @org.springframework.context.annotation.Bean
        TestConsumer testConsumer() {
            return new TestConsumer();
        }
    }

    /** Typed-консьюмер: тип параметра метода — единственный источник целевого типа (без headers). */
    @Component
    static class TestConsumer {
        private final BlockingQueue<ScanRequestedEvent> queue = new LinkedBlockingQueue<>();

        @KafkaListener(topics = TOPIC, groupId = "compliance-worker-it")
        void onMessage(ScanRequestedEvent event, Acknowledgment ack) {
            queue.add(event);
            ack.acknowledge();
        }

        ScanRequestedEvent poll(int seconds) throws InterruptedException {
            return queue.poll(seconds, TimeUnit.SECONDS);
        }
    }
}
