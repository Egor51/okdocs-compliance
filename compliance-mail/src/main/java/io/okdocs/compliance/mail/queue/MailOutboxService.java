package io.okdocs.compliance.mail.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.mail.model.MailType;
import io.okdocs.compliance.mail.security.MailPayloadCipher;
import io.okdocs.compliance.mail.template.HandlebarsMailTemplateRenderer;
import io.okdocs.compliance.persistence.mail.MailOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
public class MailOutboxService {

    private final MailOutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final MailPayloadCipher payloadCipher;

    @Transactional
    public boolean enqueue(MailType type,
                           String idempotencyKey,
                           String aggregateId,
                           String recipient,
                           String subject,
                           String templateName,
                           String locale,
                           Map<String, Object> model) {
        String normalizedLocale = HandlebarsMailTemplateRenderer.normalizeLocale(locale);
        String payload;
        try {
            payload = payloadCipher.encrypt(objectMapper.writeValueAsString(model));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize mail template model", e);
        }
        return repository.insertPending(
                UUID.randomUUID(), idempotencyKey, type.name(), aggregateId,
                recipient.trim(), subject, templateName, normalizedLocale, payload, Instant.now()) > 0;
    }
}
