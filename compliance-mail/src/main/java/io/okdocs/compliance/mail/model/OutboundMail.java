package io.okdocs.compliance.mail.model;

import java.util.UUID;

public record OutboundMail(
        UUID messageId,
        MailType type,
        String recipient,
        String subject,
        String htmlBody,
        String textBody,
        String replyTo
) {
}
