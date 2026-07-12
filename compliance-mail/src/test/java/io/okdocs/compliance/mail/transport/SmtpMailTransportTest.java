package io.okdocs.compliance.mail.transport;

import io.okdocs.compliance.mail.config.ComplianceMailProperties;
import io.okdocs.compliance.mail.model.MailDeliveryResult;
import io.okdocs.compliance.mail.model.MailType;
import io.okdocs.compliance.mail.model.OutboundMail;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmtpMailTransportTest {

    @Test
    void sendsPlainTextAndHtmlAsMultipartMessage() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);

        SmtpMailTransport transport = new SmtpMailTransport(sender, properties());
        OutboundMail mail = new OutboundMail(
                UUID.randomUUID(), MailType.WELCOME, "user@example.com", "Welcome",
                "<strong>Welcome</strong>", "Welcome", "support@okdocs.io");

        MailDeliveryResult result = transport.send(mail);

        assertThat(result.outcome()).isEqualTo(MailDeliveryResult.Outcome.DELIVERED);
        message.saveChanges();
        assertThat(message.isMimeType("multipart/*")).isTrue();
        verify(sender).send(message);
    }

    private static ComplianceMailProperties properties() {
        return new ComplianceMailProperties(true, "support@okdocs.io", null,
                "http://localhost:3000", "01234567890123456789012345678901", null,
                null, null, null, null, null);
    }
}
