package io.okdocs.compliance.mail.transport;

import io.okdocs.compliance.mail.model.MailDeliveryResult;
import io.okdocs.compliance.mail.model.OutboundMail;

public interface MailTransport {
    MailDeliveryResult send(OutboundMail mail);
}
