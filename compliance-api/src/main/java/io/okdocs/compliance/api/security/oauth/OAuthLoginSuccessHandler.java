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
import java.util.Set;

/**
 * Финал OAuth-флоу (F.8): после успешного обмена code→token и загрузки профиля Spring вызывает этот
 * handler. Мы маппим профиль → {@link OAuthUserInfo}, резолвим/создаём аккаунт ({@code resolveOrCreate}),
 * выпускаем one-time код автологина и редиректим на фронт с {@code ?code=...} (JWT не светим в URL —
 * фронт/BFF меняет код на токены через {@code /api/auth/oauth/exchange}).
 * <p>
 * Locale интерфейса прокидывается через {@code state} ({@link LocaleAwareAuthorizationRequestResolver}):
 * читаем его из вернувшегося state и подставляем в плейсхолдер {@code {locale}} в
 * {@code successRedirectUrl} (фронт next-intl с localePrefix=always ждёт {@code /{locale}/...}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

    /** Поддерживаемые locale интерфейса — зеркалят фронтовый routing (next-intl). */
    private static final Set<String> SUPPORTED_LOCALES = Set.of("ru", "en");
    /** Locale по умолчанию — зеркалит defaultLocale фронта. Используется, если state без locale. */
    static final String DEFAULT_LOCALE = "ru";
    /** Плейсхолдер locale в success-redirect-url (compliance.oauth.success-redirect-url). */
    private static final String LOCALE_PLACEHOLDER = "{locale}";

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

        String locale = localeFromState(request.getParameter("state"));
        // Подставляем locale ДО парсинга URI: иначе '{'/'}' плейсхолдера ломают UriComponentsBuilder.
        String template = properties.oauth().successRedirectUrl().replace(LOCALE_PLACEHOLDER, locale);
        String redirect = UriComponentsBuilder.fromUriString(template)
                .queryParam("code", URLEncoder.encode(code, StandardCharsets.UTF_8))
                .build(true)
                .toUriString();
        log.info("OAuth success: provider={} userId={} locale={} → redirect с one-time кодом",
                registrationId, user.getId(), locale);
        response.sendRedirect(redirect);
    }

    /** {@code true} для непустого поддерживаемого locale (используется и резолвером). */
    static boolean isSupportedLocale(String locale) {
        return locale != null && SUPPORTED_LOCALES.contains(locale);
    }

    /**
     * Достаёт locale из суффикса {@code state} ({@code <state>__<locale>}; см.
     * {@link LocaleAwareAuthorizationRequestResolver}). Нет суффикса / неподдерживаемый —
     * {@link #DEFAULT_LOCALE}.
     */
    static String localeFromState(String state) {
        if (state == null) {
            return DEFAULT_LOCALE;
        }
        int sep = state.lastIndexOf(LocaleAwareAuthorizationRequestResolver.LOCALE_SEPARATOR);
        if (sep < 0) {
            return DEFAULT_LOCALE;
        }
        String locale = state.substring(sep + LocaleAwareAuthorizationRequestResolver.LOCALE_SEPARATOR.length());
        return isSupportedLocale(locale) ? locale : DEFAULT_LOCALE;
    }
}
