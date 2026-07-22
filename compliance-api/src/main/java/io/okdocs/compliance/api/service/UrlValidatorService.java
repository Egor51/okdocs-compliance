package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.security.BlockedDomainPolicy;
import io.okdocs.compliance.contracts.security.HttpUrlNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;

/**
 * SSRF-защита для целевого URL скана (§4.2): допускает только http/https, резолвит host
 * и отклоняет приватные/loopback/link-local/мультикаст-адреса. Возвращает нормализованный URL
 * и извлечённый домен.
 */
@Slf4j
@Service
public class UrlValidatorService {

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final HostResolver hostResolver;
    private final BlockedDomainPolicy blockedDomainPolicy;

    @Autowired
    public UrlValidatorService(ComplianceApiProperties properties) {
        this(InetAddress::getAllByName, properties.security().blockedDomains());
    }

    UrlValidatorService(HostResolver hostResolver) {
        this(hostResolver, List.of());
    }

    UrlValidatorService(HostResolver hostResolver, Collection<String> blockedDomains) {
        this.hostResolver = hostResolver;
        this.blockedDomainPolicy = new BlockedDomainPolicy(blockedDomains);
    }

    public record ValidatedUrl(String normalizedUrl, String domain) {
    }

    public ValidatedUrl validate(String rawUrl) {
        HttpUrlNormalizer.NormalizedHttpUrl normalized;
        try {
            normalized = HttpUrlNormalizer.normalize(rawUrl, true);
        } catch (IllegalArgumentException e) {
            throw new ComplianceValidationException(e.getMessage());
        }
        String domain = normalized.host();

        if (blockedDomainPolicy.isBlocked(domain)) {
            throw new ComplianceValidationException("Сканирование этого домена запрещено");
        }

        assertResolvesToPublicAddress(domain);

        return new ValidatedUrl(normalized.url(), domain);
    }

    private void assertResolvesToPublicAddress(String host) {
        InetAddress[] addresses;
        try {
            addresses = hostResolver.resolve(host);
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
