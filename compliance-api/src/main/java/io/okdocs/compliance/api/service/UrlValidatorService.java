package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * SSRF-защита для целевого URL скана (§4.2): допускает только http/https, резолвит host
 * и отклоняет приватные/loopback/link-local/мультикаст-адреса. Возвращает нормализованный URL
 * и извлечённый домен.
 */
@Slf4j
@Service
public class UrlValidatorService {

    public record ValidatedUrl(String normalizedUrl, String domain) {
    }

    public ValidatedUrl validate(String rawUrl) {
        String trimmed = rawUrl == null ? "" : rawUrl.trim();
        if (trimmed.isEmpty()) {
            throw new ComplianceValidationException("URL не указан");
        }
        if (!trimmed.matches("(?i)^https?://.*")) {
            trimmed = "https://" + trimmed;
        }

        URI uri = parse(trimmed);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ComplianceValidationException("Поддерживаются только http и https");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ComplianceValidationException("Не удалось определить хост из URL");
        }
        String domain = IDN.toASCII(host).toLowerCase(Locale.ROOT);

        assertResolvesToPublicAddress(domain);

        return new ValidatedUrl(uri.toString(), domain);
    }

    private URI parse(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new ComplianceValidationException("Некорректный URL: " + e.getMessage());
        }
    }

    private void assertResolvesToPublicAddress(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ComplianceValidationException("Домен не резолвится: " + host);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                log.warn("Заблокирован SSRF-адрес {} для хоста {}", address.getHostAddress(), host);
                throw new ComplianceValidationException("Адрес сайта недопустим (приватная сеть)");
            }
        }
    }

    private boolean isBlocked(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress();
    }
}
