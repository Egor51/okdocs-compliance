package io.okdocs.compliance.mail.transport;

import io.okdocs.compliance.mail.model.MailDeliveryResult;
import io.okdocs.compliance.mail.model.OutboundMail;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DisabledMailTransport implements MailTransport {
    @Override
    public MailDeliveryResult send(OutboundMail mail) {
        log.info("Mail simulated: messageId={} type={}", mail.messageId(), mail.type());
        return MailDeliveryResult.simulated();
    }
}
