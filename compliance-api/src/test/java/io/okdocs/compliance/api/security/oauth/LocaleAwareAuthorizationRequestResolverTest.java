package io.okdocs.compliance.api.security.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.assertj.core.api.Assertions.assertThat;

class LocaleAwareAuthorizationRequestResolverTest {

    private final ClientRegistration google = CommonOAuth2Provider.GOOGLE
            .getBuilder("google").clientId("id").clientSecret("secret").build();
    private final LocaleAwareAuthorizationRequestResolver resolver =
            new LocaleAwareAuthorizationRequestResolver(
                    new InMemoryClientRegistrationRepository(google), "/oauth2/authorization");

    private MockHttpServletRequest startRequest(String localeParam) {
        var req = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        req.setServletPath("/oauth2/authorization/google");
        if (localeParam != null) {
            req.setParameter("ui_locale", localeParam);
        }
        return req;
    }

    @Test
    void appendsSupportedLocaleToState() {
        OAuth2AuthorizationRequest req = resolver.resolve(startRequest("en"));

        assertThat(req).isNotNull();
        assertThat(req.getState()).endsWith(".locale.en");
        // Round-trip: success-handler достаёт из этого же state именно en.
        assertThat(OAuthLoginSuccessHandler.localeFromState(req.getState())).isEqualTo("en");
    }

    @Test
    void leavesStateUntouchedWhenLocaleMissing() {
        OAuth2AuthorizationRequest req = resolver.resolve(startRequest(null));

        assertThat(req).isNotNull();
        assertThat(req.getState()).doesNotContain(".locale.");
    }

    @Test
    void leavesStateUntouchedWhenLocaleUnsupported() {
        OAuth2AuthorizationRequest req = resolver.resolve(startRequest("de"));

        assertThat(req).isNotNull();
        assertThat(req.getState()).doesNotContain(".locale.");
    }

    @Test
    void returnsNullForUnmatchedRequest() {
        var req = new MockHttpServletRequest("GET", "/something/else");
        req.setServletPath("/something/else");
        assertThat(resolver.resolve(req)).isNull();
    }
}
