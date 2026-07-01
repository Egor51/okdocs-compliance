package io.okdocs.compliance.api.service.payment;

import io.okdocs.compliance.api.config.YooKassaProperties;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Валидация {@code returnUrl} из запроса против allowlist хостов (анти-open-redirect): после оплаты
 * провайдер уводит юзера по нашему URL, поэтому произвольный домен из запроса = open redirect.
 * <p>
 * Нет {@code returnUrl} → дефолт магазина. Есть, но host не в allowlist (или схема не http(s)) →
 * fail-fast 400 (не silent fallback: иначе фронт думает, что URL принят, а юзер уезжает не туда).
 */
@Component
@RequiredArgsConstructor
public class ReturnUrlValidator {

    private final YooKassaProperties properties;

    /** Возвращает валидный returnUrl: дефолт магазина или проверенный override из запроса. */
    public String resolve(String requested) {
        if (requested == null || requested.isBlank()) {
            return properties.returnUrl();
        }
        URI uri;
        try {
            uri = new URI(requested.trim());
        } catch (URISyntaxException e) {
            throw new ComplianceValidationException("Некорректный returnUrl");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new ComplianceValidationException("returnUrl должен быть http(s) URL с хостом");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        boolean allowed = properties.allowedReturnHosts().stream()
                .anyMatch(h -> h.equalsIgnoreCase(normalizedHost));
        if (!allowed) {
            throw new ComplianceValidationException("Домен returnUrl не разрешён: " + normalizedHost);
        }
        return requested.trim();
    }
}
