package io.okdocs.compliance.api.security.oauth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Строит {@link ClientRegistrationRepository} ТОЛЬКО из сконфигурированных провайдеров (F.8, P3).
 * <p>
 * Провайдеры включаются НЕЗАВИСИМО: запись с пустым client-id отбрасывается (в отличие от штатного
 * binding'а Spring Boot, который падал бы на пустом client-id). Если не сконфигурирован ни один
 * провайдер — bean {@link ClientRegistrationRepository} НЕ создаётся, и OAuth-цепочка
 * {@code SecurityConfig} остаётся выключенной (приложение стартует без соц-логина).
 * <p>
 * {@code @ConditionalOnMissingBean} уважает штатный auto-config: если оператор предпочтёт настроить
 * {@code spring.security.oauth2.client.*} напрямую, наш bean не вмешается.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(OAuthClientProperties.class)
public class OAuthClientRegistrationConfig {

    private static final String REDIRECT_URI = "{baseUrl}/login/oauth2/code/{registrationId}";

    /** registrationId → фабрика ClientRegistration.Builder по кредам. */
    private final Map<String, BiFunction<String, String, ClientRegistration.Builder>> builders = Map.of(
            "google", (id, secret) -> CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(id).clientSecret(secret),
            "github", (id, secret) -> CommonOAuth2Provider.GITHUB.getBuilder("github")
                    .clientId(id).clientSecret(secret),
            "yandex", OAuthClientRegistrationConfig::yandexBuilder,
            "vk", OAuthClientRegistrationConfig::vkBuilder);

    /**
     * Bean создаётся ТОЛЬКО когда настроен хотя бы один провайдер ({@link
     * OAuthProvidersConfiguredCondition}) — иначе bean definition не регистрируется вовсе. Тем же
     * условием гейтится и OAuth-{@code SecurityFilterChain} в {@code SecurityConfig}, поэтому при
     * отсутствии провайдеров OAuth-цепочка не включается (метод, возвращающий {@code null}, всё равно
     * создавал бы фантомное определение, на которое среагировал бы {@code @ConditionalOnBean}).
     * {@code @ConditionalOnMissingBean} уважает штатный auto-config, если оператор настроит
     * {@code spring.security.oauth2.client.*} напрямую.
     */
    @Bean
    @Conditional(OAuthProvidersConfiguredCondition.class)
    @ConditionalOnMissingBean(ClientRegistrationRepository.class)
    public ClientRegistrationRepository clientRegistrationRepository(OAuthClientProperties props) {
        List<ClientRegistration> registrations = new ArrayList<>();
        props.providers().forEach((id, creds) -> {
            var factory = builders.get(id.toLowerCase());
            if (factory == null) {
                log.warn("OAuth: неизвестный провайдер '{}' в compliance.oauth.client.providers — пропуск", id);
                return;
            }
            if (creds == null || !creds.isConfigured()) {
                return; // пустой client-id/secret → провайдер выключен
            }
            registrations.add(factory.apply(creds.clientId(), creds.clientSecret()).build());
            log.info("OAuth провайдер включён: {}", id);
        });
        // Условие гарантирует ≥1 сконфигурированного провайдера, поэтому список не пуст.
        return new InMemoryClientRegistrationRepository(registrations);
    }

    /** Яндекс — кастомные endpoints (нет в CommonOAuth2Provider). */
    private static ClientRegistration.Builder yandexBuilder(String clientId, String clientSecret) {
        return ClientRegistration.withRegistrationId("yandex")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT_URI)
                .scope("login:email", "login:info")
                .authorizationUri("https://oauth.yandex.ru/authorize")
                .tokenUri("https://oauth.yandex.ru/token")
                .userInfoUri("https://login.yandex.ru/info?format=json")
                .userNameAttributeName("id")
                .clientName("Yandex");
    }

    /** VK — кастомные endpoints. */
    private static ClientRegistration.Builder vkBuilder(String clientId, String clientSecret) {
        return ClientRegistration.withRegistrationId("vk")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT_URI)
                .scope("email")
                .authorizationUri("https://oauth.vk.com/authorize")
                .tokenUri("https://oauth.vk.com/access_token")
                .userInfoUri("https://api.vk.com/method/users.get?v=5.131")
                .userNameAttributeName("id")
                .clientName("VK");
    }
}
