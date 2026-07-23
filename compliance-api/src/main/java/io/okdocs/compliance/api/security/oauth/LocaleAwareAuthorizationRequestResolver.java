package io.okdocs.compliance.api.security.oauth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Прокидывает locale интерфейса через OAuth-handshake (F.8): бэкенд не знает язык фронта на момент
 * success-handler'а, поэтому язык, с которого пользователь начал вход, должен пройти полный круг
 * (наш сервер → провайдер → наш callback).
 * <p>
 * Несём locale внутри самого {@code state}: к сгенерированному Spring'ом значению дописываем
 * {@code <state>}{@value #LOCALE_SEPARATOR}{@code <locale>}. Spring хранит ровно это значение в сессии и
 * проверяет его точным сравнением при возврате — значит наш суффикс корректно round-trip'ится и не ломает
 * CSRF-защиту (в отличие от {@code additionalParameters}, недоступных в success-handler'е по
 * {@code HttpServletRequest}). Locale читается из query {@value #LOCALE_PARAM} запроса старта; при
 * отсутствии/невалидности суффикс не добавляется и success-handler берёт locale по умолчанию.
 *
 * @see OAuthLoginSuccessHandler#localeFromState(String)
 */
public class LocaleAwareAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    /** Query-параметр старта OAuth (BFF: /api/auth/oauth/{provider}?ui_locale=ru). */
    static final String LOCALE_PARAM = "ui_locale";
    /**
     * Разделитель в state между CSRF-значением и locale. Точка не входит в Base64URL-алфавит,
     * которым Spring генерирует исходный state, поэтому случайное значение не может имитировать
     * locale-суффикс.
     */
    static final String LOCALE_SEPARATOR = ".locale.";

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public LocaleAwareAuthorizationRequestResolver(ClientRegistrationRepository repo,
                                                   String authorizationRequestBaseUri) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(repo, authorizationRequestBaseUri);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return withLocale(delegate.resolve(request), request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return withLocale(delegate.resolve(request, clientRegistrationId), request);
    }

    /** Дописывает валидный locale в state; null-request (нет совпадения) пробрасывает как есть. */
    private OAuth2AuthorizationRequest withLocale(OAuth2AuthorizationRequest req, HttpServletRequest request) {
        if (req == null) {
            return null;
        }
        String locale = request.getParameter(LOCALE_PARAM);
        if (!OAuthLoginSuccessHandler.isSupportedLocale(locale)) {
            return req;
        }
        return OAuth2AuthorizationRequest.from(req)
                .state(req.getState() + LOCALE_SEPARATOR + locale)
                .build();
    }
}
