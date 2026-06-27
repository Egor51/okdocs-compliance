package io.okdocs.compliance.api.security.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Креды OAuth-провайдеров под СОБСТВЕННЫМ префиксом {@code compliance.oauth.providers.*} (F.8).
 * <p>
 * Намеренно НЕ используем штатный {@code spring.security.oauth2.client.registration.*}: Spring Boot
 * валидирует его при старте и падает с {@code clientId cannot be empty} на любой регистрации с
 * пустым client-id — это ломало бы независимое включение провайдеров (хочу только Google для smoke).
 * Здесь пустые/отсутствующие записи просто отбрасываются {@link OAuthClientRegistrationConfig}.
 *
 * @param providers map registrationId ({@code google|github|yandex|vk}) → креды
 */
@ConfigurationProperties(prefix = "compliance.oauth.client")
public record OAuthClientProperties(Map<String, Credentials> providers) {

    public OAuthClientProperties {
        if (providers == null) {
            providers = Map.of();
        }
    }

    /**
     * @param clientId     client-id; пустой/null → провайдер выключен
     * @param clientSecret client-secret; тоже обязателен — иначе обмен authorization code упадёт
     */
    public record Credentials(String clientId, String clientSecret) {
        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }
}
