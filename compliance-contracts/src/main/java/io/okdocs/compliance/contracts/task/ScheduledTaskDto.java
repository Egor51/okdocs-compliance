package io.okdocs.compliance.contracts.task;

import io.okdocs.compliance.contracts.enums.TaskStatus;
import io.okdocs.compliance.contracts.enums.TaskType;

import java.time.Instant;
import java.util.UUID;

/** Представление запланированной задачи. */
public record ScheduledTaskDto(
        UUID id,
        TaskType type,
        TaskStatus status,
        UUID scanId,
        Long userId,
        UUID guestId,
        Instant scheduledAt,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage
) {
}
