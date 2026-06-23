package io.okdocs.compliance.api.security.oauth;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.service.OAuthAccountService;
import io.okdocs.compliance.api.service.OAuthLoginCodeService;
import io.okdocs.compliance.contracts.auth.OAuthUserInfo;
import io.okdocs.compliance.contracts.enums.OAuthProvider;
import io.okdocs.compliance.persistence.auth.AppUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthLoginSuccessHandlerTest {

    @Mock private OAuthAccountService accountService;
    @Mock private OAuthLoginCodeService loginCodeService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;

    private OAuthLoginSuccessHandler handler;

    @BeforeEach
    void setUp() {
        ComplianceApiProperties props = new ComplianceApiProperties(
                null, null, null, null, null, null, null,
                new ComplianceApiProperties.Oauth("https://app.example.com/auth/callback"), null);
        handler = new OAuthLoginSuccessHandler(accountService, loginCodeService, props);
    }

    @Test
    void resolvesAccountIssuesCodeAndRedirectsToFrontend() throws IOException {
        OAuth2User principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", "g-1", "email", "a@gmail.com", "email_verified", true, "name", "Ann"),
                "sub");
        OAuth2AuthenticationToken token = new OAuth2AuthenticationToken(
                principal, principal.getAuthorities(), "google");

        AppUser user = new AppUser();
        user.setId(55L);
        when(accountService.resolveOrCreate(any(OAuthUserInfo.class))).thenReturn(user);
        when(loginCodeService.issue(55L)).thenReturn("one-time-code");

        handler.onAuthenticationSuccess(request, response, token);

        // Профиль смаплен и передан в resolveOrCreate с правильным провайдером/verified.
        ArgumentCaptor<OAuthUserInfo> info = ArgumentCaptor.forClass(OAuthUserInfo.class);
        verify(accountService).resolveOrCreate(info.capture());
        assertThat(info.getValue().provider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(info.getValue().emailVerified()).isTrue();

        // Редирект на фронт с one-time кодом (JWT в URL не светим).
        ArgumentCaptor<String> redirect = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(redirect.capture());
        assertThat(redirect.getValue())
                .startsWith("https://app.example.com/auth/callback")
                .contains("code=one-time-code");
    }
}
