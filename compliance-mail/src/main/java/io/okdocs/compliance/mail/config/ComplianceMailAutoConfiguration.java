package io.okdocs.compliance.mail.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.mail.notification.DefaultMailNotificationService;
import io.okdocs.compliance.mail.notification.MailNotificationService;
import io.okdocs.compliance.mail.queue.MailOutboxDispatcher;
import io.okdocs.compliance.mail.queue.MailOutboxService;
import io.okdocs.compliance.mail.security.MailPayloadCipher;
import io.okdocs.compliance.mail.template.HandlebarsMailTemplateRenderer;
import io.okdocs.compliance.mail.template.MailTemplateRenderer;
import io.okdocs.compliance.mail.transport.DisabledMailTransport;
import io.okdocs.compliance.mail.transport.MailTransport;
import io.okdocs.compliance.mail.transport.SmtpMailTransport;
import io.okdocs.compliance.persistence.mail.MailOutboxRepository;
import io.okdocs.compliance.persistence.mail.EmailSubscriptionRepository;
import io.okdocs.compliance.persistence.auth.PasswordResetTokenRepository;
import io.okdocs.compliance.mail.queue.MailRetentionJob;
import io.micrometer.core.instrument.MeterRegistry;
import io.okdocs.compliance.mail.subscription.EmailSubscriptionService;
import io.okdocs.compliance.mail.notification.PromoMailService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;

@AutoConfiguration
@EnableConfigurationProperties(ComplianceMailProperties.class)
public class ComplianceMailAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MailPayloadCipher mailPayloadCipher(ComplianceMailProperties properties) {
        return new MailPayloadCipher(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    MailTemplateRenderer mailTemplateRenderer() {
        return new HandlebarsMailTemplateRenderer();
    }

    @Bean
    @ConditionalOnMissingBean
    MailTransport mailTransport(ComplianceMailProperties properties,
                                ObjectProvider<JavaMailSender> mailSender) {
        if (!properties.enabled()) return new DisabledMailTransport();
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("spring.mail.host must be configured when mail is enabled");
        }
        return new SmtpMailTransport(sender, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    MailOutboxService mailOutboxService(MailOutboxRepository repository,
                                        ObjectMapper objectMapper,
                                        MailPayloadCipher cipher) {
        return new MailOutboxService(repository, objectMapper, cipher);
    }

    @Bean
    @ConditionalOnMissingBean
    MailNotificationService mailNotificationService(MailOutboxService outboxService) {
        return new DefaultMailNotificationService(outboxService);
    }

    @Bean
    @ConditionalOnMissingBean
    EmailSubscriptionService emailSubscriptionService(EmailSubscriptionRepository repository,
                                                       ComplianceMailProperties properties) {
        return new EmailSubscriptionService(repository, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    PromoMailService promoMailService(EmailSubscriptionService subscriptions,
                                      MailNotificationService notifications) {
        return new PromoMailService(subscriptions, notifications);
    }

    @Bean
    @ConditionalOnMissingBean
    MailMetrics mailMetrics(ObjectProvider<MeterRegistry> registry) {
        return new MailMetrics(registry.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    MailRetentionJob mailRetentionJob(MailOutboxRepository mailRepository,
                                      PasswordResetTokenRepository resetTokenRepository,
                                      ComplianceMailProperties properties) {
        return new MailRetentionJob(mailRepository, resetTokenRepository, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    MailOutboxDispatcher mailOutboxDispatcher(MailOutboxRepository repository,
                                              ObjectMapper objectMapper,
                                              MailPayloadCipher cipher,
                                              MailTemplateRenderer renderer,
                                              MailTransport transport,
                                              ComplianceMailProperties properties,
                                              EmailSubscriptionRepository subscriptionRepository,
                                              MailMetrics metrics,
                                              PlatformTransactionManager transactionManager) {
        return new MailOutboxDispatcher(repository, objectMapper, cipher, renderer,
                transport, properties, subscriptionRepository, metrics, transactionManager);
    }
}
