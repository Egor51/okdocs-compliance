package io.okdocs.compliance.api.web;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Определяет IP клиента. {@code X-Forwarded-For} учитывается ТОЛЬКО при включённом
 * {@code compliance.security.trust-forwarded-header} (за доверенным proxy) — иначе анонимный
 * клиент подделкой заголовка обошёл бы guest-rate-limit по IP. По умолчанию берётся
 * {@code remoteAddr}.
 */
@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private final ComplianceApiProperties properties;

    public String resolve(HttpServletRequest request) {
        if (properties.security().trustForwardedHeader()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
