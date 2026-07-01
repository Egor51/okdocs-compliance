package io.okdocs.compliance.api.security;

import io.okdocs.compliance.api.security.oauth.LocaleAwareAuthorizationRequestResolver;
import io.okdocs.compliance.api.security.oauth.OAuthLoginSuccessHandler;
import io.okdocs.compliance.api.security.oauth.OAuthProvidersConfiguredCondition;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Безопасность (§4.3, F.8).
 * <p>
 * Две цепочки. <b>OAuth2-цепочка</b> ({@link #oauthSecurityFilterChain}, {@code @Order(1)}) узко
 * матчит {@code /oauth2/**} + {@code /login/oauth2/**} и разрешает сессию на время handshake'а
 * (state/PKCE между уходом к провайдеру и возвратом). <b>Основная API-цепочка</b> ({@code @Order(2)})
 * остаётся чисто stateless JWT для всего остального.
 * <ul>
 *   <li>Открытые: {@code /api/auth/guest|login|register|refresh|logout|me|oauth/exchange}, webhook, actuator.</li>
 *   <li>{@code /api/cabinet/**} — роль {@code USER}; {@code /api/admin/**} — {@code ADMIN}.</li>
 *   <li>scan-эндпоинты — любой валидный токен; owner-check в сервисе.</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * OAuth2-login цепочка. Создаётся ТОЛЬКО если сконфигурирован хотя бы один провайдер.
     * Проверяем credentials тем же условием, что и {@link ClientRegistrationRepository}: обычный
     * {@code @ConditionalOnBean} здесь ненадёжен, поскольку порядок обработки пользовательских
     * {@code @Configuration} не гарантирует, что definition репозитория уже виден. Сессия разрешена:
     * OAuth2 хранит state между редиректами.
     */
    @Bean
    @Order(1)
    @Conditional(OAuthProvidersConfiguredCondition.class)
    public SecurityFilterChain oauthSecurityFilterChain(HttpSecurity http,
                                                        OAuthLoginSuccessHandler successHandler,
                                                        ClientRegistrationRepository clientRegistrationRepository) throws Exception {
        // Кастомный resolver несёт locale интерфейса через state (F.8) — см. success-handler.
        var authorizationRequestResolver = new LocaleAwareAuthorizationRequestResolver(
                clientRegistrationRepository, "/oauth2/authorization");
        http
                .securityMatcher("/oauth2/**", "/login/oauth2/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(ep -> ep.authorizationRequestResolver(authorizationRequestResolver))
                        .successHandler(successHandler));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // открытые auth-эндпоинты
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/guest",
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/refresh",
                                "/api/auth/logout",
                                "/api/auth/oauth/exchange").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").permitAll()
                        // публичный продуктовый каталог для маркетинга и формы запуска scan
                        .requestMatchers(HttpMethod.GET,
                                "/api/jurisdictions",
                                "/api/jurisdictions/**",
                                "/api/pricing/plans",
                                "/api/pricing/plans/**").permitAll()
                        // webhook оплаты — у провайдера нет JWT; подлинность через fail-closed
                        // shared-secret + remote-проверку в обработчике (docs/PLAN-payments.md).
                        .requestMatchers(HttpMethod.POST, "/api/payments/webhooks/**").permitAll()
                        // actuator
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // ролевые зоны
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/cabinet/**").hasRole("USER")
                        // платежи (создание/статус) — только USER; webhook выше уже permit'нут
                        .requestMatchers("/api/payments/**").hasRole("USER")
                        // история сканов — только USER (§4.3)
                        .requestMatchers(HttpMethod.GET, "/api/compliance-scans").hasRole("USER")
                        // free marketing scan — любой валидный токен (guest получает его через
                        // /api/auth/guest; так rate-limit по IP и owner-check работают единообразно)
                        .requestMatchers(HttpMethod.POST, "/api/free-scans").authenticated()
                        // остальные scan-эндпоинты — любой валидный токен; owner-check в сервисе
                        .requestMatchers("/api/compliance-scans/**").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
