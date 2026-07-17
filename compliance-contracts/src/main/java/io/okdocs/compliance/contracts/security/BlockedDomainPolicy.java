package io.okdocs.compliance.contracts.security;

import java.net.IDN;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Единая anti-abuse политика доменов для API и worker.
 *
 * <p>Элемент списка блокирует точный домен и любые его поддомены, но не похожие суффиксы:
 * {@code gov.ru} блокирует {@code duma.gov.ru}, но не {@code notgov.ru}. И входной host, и записи
 * конфигурации нормализуются одинаково (IDN ASCII, lowercase, без завершающей точки).</p>
 */
public final class BlockedDomainPolicy {

    private final List<String> blockedDomains;

    public BlockedDomainPolicy(Collection<String> blockedDomains) {
        Collection<String> source = blockedDomains == null ? List.of() : blockedDomains;
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String domain : source) {
            normalized.add(normalizeConfiguredDomain(domain));
        }
        this.blockedDomains = List.copyOf(normalized);
    }

    public boolean isBlocked(String host) {
        String normalizedHost = normalizeHost(host);
        if (normalizedHost == null) {
            return false;
        }
        return blockedDomains.stream()
                .anyMatch(blocked -> normalizedHost.equals(blocked)
                        || normalizedHost.endsWith("." + blocked));
    }

    public List<String> blockedDomains() {
        return blockedDomains;
    }

    private static String normalizeConfiguredDomain(String domain) {
        Objects.requireNonNull(domain, "blocked domain must not be null");
        String normalized = normalizeHost(domain);
        if (normalized == null || domain.contains(":") || domain.contains("/")
                || domain.contains("@") || domain.contains("*")) {
            throw new IllegalArgumentException(
                    "blocked domain must be a hostname without scheme, path or wildcard: " + domain);
        }
        return normalized;
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String value = host.trim();
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isBlank()) {
            return null;
        }
        try {
            return IDN.toASCII(value).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
