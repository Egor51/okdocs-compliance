package io.okdocs.compliance.persistence.mail;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmailSubscriptionRepository extends JpaRepository<EmailSubscription, UUID> {
    Optional<EmailSubscription> findByNormalizedEmail(String normalizedEmail);
}
