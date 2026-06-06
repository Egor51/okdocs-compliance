package io.okdocs.compliance.api.security;

import io.jsonwebtoken.JwtException;
import io.okdocs.compliance.contracts.enums.UserStatus;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Парсит {@code Authorization: Bearer <jwt>} (guest или user) и кладёт {@link CompliancePrincipal}
 * в {@code SecurityContext}. Невалидный/отсутствующий токен оставляет запрос анонимным — решение
 * «401 или нет» принимает {@code SecurityConfig} по правилам доступа (так {@code GET /auth/me}
 * без токена отвечает {@code authenticated=false}, а не падает).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AppUserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                CompliancePrincipal principal = jwtService.parse(token);
                if (isActive(principal)) {
                    var authentication = new UsernamePasswordAuthenticationToken(
                            principal, null, authorities(principal));
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (JwtException | IllegalArgumentException e) {
                log.debug("Отклонён невалидный JWT: {}", e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Проверка актуального статуса юзера на каждом запросе: токен заблокированного/удалённого
     * аккаунта перестаёт действовать сразу, а не до истечения TTL (админский block иначе ждал бы
     * до 30 мин). Trade-off: +1 чтение БД на аутентифицированный запрос. Гость статуса не имеет.
     */
    private boolean isActive(CompliancePrincipal principal) {
        if (!principal.isUser()) {
            return true;
        }
        return userRepository.findById(principal.userId())
                .map(u -> u.getStatus() == UserStatus.ACTIVE)
                .orElse(false);
    }

    private static List<GrantedAuthority> authorities(CompliancePrincipal principal) {
        return switch (principal.type()) {
            case GUEST -> List.of(new SimpleGrantedAuthority("ROLE_GUEST"));
            case USER -> List.of(new SimpleGrantedAuthority("ROLE_USER"));
            case ADMIN -> List.of(
                    new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("ROLE_USER"));
        };
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}
