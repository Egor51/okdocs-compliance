package io.okdocs.compliance.api.security.oauth;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.service.OAuthAccountService;
import io.okdocs.compliance.api.service.OAuthLoginCodeService;
import io.okdocs.compliance.contracts.auth.OAuthUserInfo;
import io.okdocs.compliance.persistence.auth.AppUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;

/**
 * Финал OAuth-флоу (F.8): после успешного обмена code→token и загрузки профиля Spring вызывает этот
 * handler. Мы маппим профиль → {@link OAuthUserInfo}, резолвим/создаём аккаунт ({@code resolveOrCreate}),
 * выпускаем one-time код автологина и редиректим на фронт с {@code ?code=...} (JWT не светим в URL —
 * фронт/BFF меняет код на токены через {@code /api/auth/oauth/exchange}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final OAuthAccountService accountService;
    private final OAuthLoginCodeService loginCodeService;
    private final ComplianceApiProperties properties;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        String registrationId = token.getAuthorizedClientRegistrationId();
        OAuth2User principal = token.getPrincipal();

        OAuthUserInfo info = OAuthUserInfoFactory.from(registrationId, principal.getAttributes());
        AppUser user = accountService.resolveOrCreate(info);
        String code = loginCodeService.issue(user.getId());

        String redirect = UriComponentsBuilder.fromUriString(properties.oauth().successRedirectUrl())
                .queryParam("code", URLEncoder.encode(code, StandardCharsets.UTF_8))
                .build(true)
                .toUriString();
        log.info("OAuth success: provider={} userId={} → redirect с one-time кодом", registrationId, user.getId());
        response.sendRedirect(redirect);
    }
}
