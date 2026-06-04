package io.okdocs.compliance.contracts.enums;

/**
 * Статус события в transactional outbox. Всего три состояния:
 * <ul>
 *   <li>{@code PENDING} — в работе (включая ожидание ретрая). Сбой публикации не меняет статус,
 *       лишь двигает retryCount/nextAttemptAt.</li>
 *   <li>{@code PUBLISHED} — успешно опубликовано в Kafka.</li>
 *   <li>{@code DEAD} — исчерпаны ретраи.</li>
 * </ul>
 * Промежуточного {@code FAILED} нет намеренно: он был бы мёртвым значением и ломал бы выборку по
 * {@code PENDING}.
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    DEAD
}
