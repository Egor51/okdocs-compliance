package io.okdocs.compliance.api.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT-безопасность (§4.3).
 * <ul>
 *   <li>Открытые: {@code /api/auth/guest|login|register|refresh|logout|me}, actuator health.</li>
 *   <li>{@code /api/cabinet/**} — роль {@code USER} (admin тоже имеет ROLE_USER).</li>
 *   <li>{@code /api/admin/**} — роль {@code ADMIN}.</li>
 *   <li>scan-эндпоинты — любой валидный токен (guest или user); owner-check в сервисе.</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
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
                                "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").permitAll()
                        // actuator
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // ролевые зоны
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/cabinet/**").hasRole("USER")
                        // история сканов — только USER (§4.3)
                        .requestMatchers(HttpMethod.GET, "/api/compliance-scans").hasRole("USER")
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
