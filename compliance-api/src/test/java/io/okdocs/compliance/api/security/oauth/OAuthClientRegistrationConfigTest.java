package io.okdocs.compliance.api.security.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 — провайдеры включаются НЕЗАВИСИМО: запись с пустым client-id отбрасывается (Spring Boot
 * штатно падал бы на пустом client-id). Ни один сконфигурированный → bean отсутствует (OAuth off).
 */
class OAuthClientRegistrationConfigTest {

    private final OAuthClientRegistrationConfig config = new OAuthClientRegistrationConfig();

    private OAuthClientProperties props(Map<String, OAuthClientProperties.Credentials> providers) {
        return new OAuthClientProperties(providers);
    }

    private OAuthClientProperties.Credentials creds(String id) {
        return new OAuthClientProperties.Credentials(id, "secret");
    }

    private OAuthClientProperties.Credentials creds(String id, String secret) {
        return new OAuthClientProperties.Credentials(id, secret);
    }

    @Test
    void buildsRepositoryFromOnlyConfiguredProviders() {
        // Google задан, остальные с пустым client-id → в репозитории только google.
        Map<String, OAuthClientProperties.Credentials> providers = new LinkedHashMap<>();
        providers.put("google", creds("g-client"));
        providers.put("github", creds(""));     // пустой → отброшен
        providers.put("yandex", creds(null));   // null → отброшен

        ClientRegistrationRepository repo = config.clientRegistrationRepository(props(providers));

        assertThat(repo).isNotNull();
        assertThat(repo.findByRegistrationId("google")).isNotNull();
        assertThat(repo.findByRegistrationId("github")).isNull();
        assertThat(repo.findByRegistrationId("yandex")).isNull();
    }

    @Test
    void buildsCustomProviderRegistrations() {
        // Яндекс/VK — кастомные endpoints (нет в CommonOAuth2Provider), должны строиться.
        Map<String, OAuthClientProperties.Credentials> providers = new LinkedHashMap<>();
        providers.put("yandex", creds("y-client"));
        providers.put("vk", creds("vk-client"));

        ClientRegistrationRepository repo = config.clientRegistrationRepository(props(providers));

        assertThat(repo).isNotNull();
        assertThat(repo.findByRegistrationId("yandex").getProviderDetails().getAuthorizationUri())
                .isEqualTo("https://oauth.yandex.ru/authorize");
        assertThat(repo.findByRegistrationId("vk").getProviderDetails().getTokenUri())
                .isEqualTo("https://oauth.vk.com/access_token");
    }

    @Test
    void skipsProviderWithBlankClientSecret() {
        // P2: client-id есть, но secret пуст → провайдер выключен (обмен code упал бы).
        Map<String, OAuthClientProperties.Credentials> providers = new LinkedHashMap<>();
        providers.put("google", creds("g-client", "g-secret"));
        providers.put("github", creds("gh-client", ""));   // пустой secret → отброшен
        providers.put("vk", creds("vk-client", null));     // null secret → отброшен

        ClientRegistrationRepository repo = config.clientRegistrationRepository(props(providers));

        assertThat(repo.findByRegistrationId("google")).isNotNull();
        assertThat(repo.findByRegistrationId("github")).isNull();
        assertThat(repo.findByRegistrationId("vk")).isNull();
    }

    @Test
    void unknownProviderIsSkipped() {
        Map<String, OAuthClientProperties.Credentials> providers = new LinkedHashMap<>();
        providers.put("facebook", creds("fb"));  // неизвестный → пропуск
        providers.put("google", creds("g"));

        ClientRegistrationRepository repo = config.clientRegistrationRepository(props(providers));

        assertThat(repo).isNotNull();
        assertThat(repo.findByRegistrationId("google")).isNotNull();
        assertThat(repo.findByRegistrationId("facebook")).isNull();
    }
}
