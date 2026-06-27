package io.okdocs.compliance.api.security.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 — bean ClientRegistrationRepository НЕ должен существовать, когда не настроен ни один провайдер.
 * Иначе {@code @ConditionalOnBean} в {@code SecurityConfig} включил бы OAuth-цепочку по фантомному
 * (null) bean'у. Проверяем именно отсутствие/наличие bean DEFINITION через ApplicationContextRunner.
 */
class OAuthProvidersConfiguredConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Отключаем штатный OAuth2 auto-config, чтобы тестировать только наш conditional bean.
            .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class))
            .withUserConfiguration(OAuthClientRegistrationConfig.class);

    @Test
    void beanAbsentWhenNoProviderConfigured() {
        runner.withPropertyValues(
                        "compliance.oauth.client.providers.google.client-id=",
                        "compliance.oauth.client.providers.google.client-secret=")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ClientRegistrationRepository.class));
    }

    @Test
    void beanAbsentWhenNoPropertiesAtAll() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(ClientRegistrationRepository.class));
    }

    @Test
    void beanAbsentWhenClientIdSetButSecretBlank() {
        runner.withPropertyValues(
                        "compliance.oauth.client.providers.google.client-id=g-client",
                        "compliance.oauth.client.providers.google.client-secret=")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ClientRegistrationRepository.class));
    }

    @Test
    void beanPresentWhenOneProviderFullyConfigured() {
        runner.withPropertyValues(
                        "compliance.oauth.client.providers.google.client-id=g-client",
                        "compliance.oauth.client.providers.google.client-secret=g-secret")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ClientRegistrationRepository.class);
                    ClientRegistrationRepository repo = ctx.getBean(ClientRegistrationRepository.class);
                    assertThat(repo.findByRegistrationId("google")).isNotNull();
                });
    }
}
