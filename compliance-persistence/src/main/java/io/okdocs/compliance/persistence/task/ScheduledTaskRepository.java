package io.okdocs.compliance.persistence.task;

import io.okdocs.compliance.contracts.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, UUID> {

    /** Dispatcher: задачи, чьё время наступило. */
    List<ScheduledTask> findTop50ByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
            TaskStatus status, Instant now);

    List<ScheduledTask> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<ScheduledTask> findByGuestIdOrderByCreatedAtDesc(UUID guestId);
}
