package io.okdocs.compliance.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.contracts.enums.OutboxStatus;
import io.okdocs.compliance.persistence.outbox.OutboxEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Сборка {@link OutboxEvent} из типизированного события: сериализует payload в JSON,
 * проставляет topic/key/eventType/status. Так логика сериализации событий живёт в messaging,
 * а api/worker лишь передают доменное событие, агрегат и топик.
 */
@Component
@RequiredArgsConstructor
public class OutboxEventFactory {

    private final ObjectMapper objectMapper;

    /**
     * @param aggregateId scanId — агрегат, к которому относится событие
     * @param topic       Kafka-топик назначения
     * @param eventKey    ключ Kafka (обычно scanId как строка)
     * @param payload     доменное событие (record из contracts)
     */
    public OutboxEvent create(UUID aggregateId, String topic, String eventKey, Object payload) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateId(aggregateId);
        event.setEventType(payload.getClass().getSimpleName());
        event.setTopic(topic);
        event.setEventKey(eventKey);
        event.setPayload(serialize(payload));
        event.setStatus(OutboxStatus.PENDING);
        return event;
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сериализовать payload события "
                    + payload.getClass().getSimpleName(), e);
        }
    }
}
