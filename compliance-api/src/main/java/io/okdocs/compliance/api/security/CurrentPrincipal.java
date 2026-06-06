package io.okdocs.compliance.api.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Доступ к {@link CompliancePrincipal} текущего запроса из {@code SecurityContext}.
 * Статический хелпер — контроллеры/сервисы не тащат зависимость на Spring Security API.
 */
public final class CurrentPrincipal {

    private CurrentPrincipal() {
    }

    public static Optional<CompliancePrincipal> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CompliancePrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    /** Принципал текущего запроса; кидает, если запрос анонимный (защищённый эндпоинт без токена). */
    public static CompliancePrincipal require() {
        return get().orElseThrow(() -> new IllegalStateException("Нет аутентифицированного принципала в контексте"));
    }
}
