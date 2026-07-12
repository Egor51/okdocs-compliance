package io.okdocs.compliance.mail.transport;

import io.okdocs.compliance.mail.config.ComplianceMailProperties;
import io.okdocs.compliance.mail.model.MailDeliveryResult;
import io.okdocs.compliance.mail.model.OutboundMail;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;

@Slf4j
@RequiredArgsConstructor
public class SmtpMailTransport implements MailTransport {

    private final JavaMailSender mailSender;
    private final ComplianceMailProperties properties;

    @Override
    public MailDeliveryResult send(OutboundMail mail) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            // setText(plainText, htmlText) creates multipart/alternative content and therefore
            // requires a multipart helper even when the message has no attachments.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.from());
            helper.setTo(mail.recipient());
            helper.setSubject(mail.subject());
            helper.setText(mail.textBody(), mail.htmlBody());
            if (mail.replyTo() != null && !mail.replyTo().isBlank()) helper.setReplyTo(mail.replyTo());
            message.setHeader("Message-ID", "<" + mail.messageId() + "@okdocs.io>");
            mailSender.send(message);
            return MailDeliveryResult.delivered();
        } catch (MailAuthenticationException e) {
            return MailDeliveryResult.permanent(e.getMessage());
        } catch (MailException e) {
            return MailDeliveryResult.retryable(e.getMessage());
        } catch (Exception e) {
            log.warn("Unable to construct email messageId={} type={}: {}",
                    mail.messageId(), mail.type(), e.getMessage());
            return MailDeliveryResult.permanent(e.getMessage());
        }
    }
}
