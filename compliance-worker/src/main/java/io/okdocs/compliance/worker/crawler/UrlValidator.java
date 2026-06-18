package io.okdocs.compliance.worker.crawler;

import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF-защита краулера: валидация публичных HTTP/HTTPS URL и хостов redirect-хопов.
 * <p>
 * Расширенная блокировка (перенесена из MVP okdocks): помимо стандартных приватных/loopback/
 * link-local/site-local диапазонов JDK дополнительно режет CGNAT (100.64.0.0/10), reserved IPv4
 * (192.0.0.0/24, 198.18.0.0/15, 203.0.113.0/24, 240.0.0.0/4), IPv6 ULA (fc00::/7), 6to4
 * (2002::/16) и IPv4-mapped IPv6 с приватным вложенным IPv4. Api-валидатор слабее — здесь, на
 * границе реальных сетевых запросов, нужна жёсткая версия.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final ComplianceWorkerProperties properties;

    public ValidationResult validate(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            return ValidationResult.invalid("URL пустой");
        }

        URI uri;
        try {
            uri = new URI(urlString.trim());
        } catch (URISyntaxException e) {
            return ValidationResult.invalid("URL некорректный");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return ValidationResult.invalid("Схема URL должна быть http или https");
        }

        String hostLower = resolveNormalizedHost(uri);
        if (hostLower == null || hostLower.isBlank()) {
            return ValidationResult.invalid("URL не содержит host");
        }

        if (isBlockedDomain(hostLower)) {
            return ValidationResult.invalid("Сканирование этого домена запрещено");
        }
        if (!isAllowedDomain(hostLower)) {
            return ValidationResult.invalid("Домен не входит в список разрешённых");
        }

        ResolvedHost resolved = resolvePublicHost(hostLower);
        if (!resolved.valid()) {
            return ValidationResult.invalid(resolved.errorMessage());
        }

        return ValidationResult.ok(hostLower);
    }

    /**
     * Проверяет, что хост redirect-хопа не ведёт на приватный/заблокированный адрес.
     * Используется в {@link SiteCrawler} при ручной обработке редиректов.
     */
    public boolean isHostSafe(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String hostLower = normalizeHost(host);
        if (hostLower == null) {
            return false;
        }
        return resolvePublicHost(hostLower).valid();
    }

    public ResolvedHost resolvePublicHost(String host) {
        return resolvePublicHost(host, true);
    }

    /**
     * Безопасный DNS-resolve для enrichment-целей (например MX), куда worker не подключается как к
     * crawl target. Allowlist доменов не применяем, но приватные/special IP и blocked domains режем.
     */
    public ResolvedHost resolvePublicDnsHost(String host) {
        return resolvePublicHost(host, false);
    }

    private ResolvedHost resolvePublicHost(String host, boolean enforceAllowedDomains) {
        String hostLower = normalizeHost(host);
        if (hostLower == null || hostLower.isBlank()) {
            return ResolvedHost.invalid("URL не содержит host");
        }
        if (isBlockedDomain(hostLower)) {
            return ResolvedHost.invalid("Сканирование этого домена запрещено");
        }
        if (enforceAllowedDomains && !isAllowedDomain(hostLower)) {
            return ResolvedHost.invalid("Домен не входит в список разрешённых");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(hostLower);
        } catch (UnknownHostException e) {
            return ResolvedHost.invalid("Не удалось разрешить DNS-имя");
        }
        for (InetAddress addr : addresses) {
            if (isPrivateOrSpecial(addr)) {
                log.warn("Заблокирован SSRF-адрес {} для хоста {}", addr.getHostAddress(), hostLower);
                return ResolvedHost.invalid("Запрещён приватный/локальный IP-адрес");
            }
        }
        return ResolvedHost.ok(hostLower, Arrays.asList(addresses));
    }

    private boolean isBlockedDomain(String host) {
        for (String blocked : properties.getCrawler().getBlockedDomains()) {
            String b = blocked.toLowerCase(Locale.ROOT);
            if (host.equals(b) || host.endsWith("." + b)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Allowlist: если {@code compliance.crawler.allowed-domains} непуст — сканировать можно ТОЛЬКО
     * перечисленные домены (и их поддомены). Пустой список = разрешены все публичные (allowlist off).
     */
    private boolean isAllowedDomain(String host) {
        List<String> allowed = properties.getCrawler().getAllowedDomains();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        for (String a : allowed) {
            String v = a.toLowerCase(Locale.ROOT);
            if (host.equals(v) || host.endsWith("." + v)) {
                return true;
            }
        }
        return false;
    }

    boolean isPrivateOrSpecial(InetAddress addr) {
        // Cloud metadata (169.254.169.254 — AWS/GCP/Azure IMDS) попадает под link-local 169.254/16
        // → режется isLinkLocalAddress(). Явный тест на этот IP — в UrlValidatorTest (SSRF-критично).
        if (addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = addr.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xff;
            int b1 = bytes[1] & 0xff;
            // 100.64.0.0/10 — CGNAT
            if (b0 == 100 && (b1 & 0xc0) == 64) {
                return true;
            }
            // 192.0.0.0/24 и 192.0.2.0/24 (b1 == 0); 198.18.0.0/15, 198.51.100.0/24;
            // 203.0.113.0/24; 240.0.0.0/4 reserved
            if (b0 == 192 && b1 == 0) {
                return true;
            }
            if (b0 == 198 && (b1 == 18 || b1 == 19 || b1 == 51)) {
                return true;
            }
            if (b0 == 203 && b1 == 0) {
                return true;
            }
            if (b0 >= 240) {
                return true;
            }
        }
        if (bytes.length == 16) {
            int b0 = bytes[0] & 0xff;
            int b1 = bytes[1] & 0xff;
            // fc00::/7 — IPv6 ULA (fc:: и fd::), не покрыт JDK isSiteLocalAddress()
            if (b0 == 0xfc || b0 == 0xfd) {
                return true;
            }
            // 2002::/16 — 6to4 tunnel (может инкапсулировать приватный IPv4)
            if (b0 == 0x20 && b1 == 0x02) {
                return true;
            }
            // ::ffff:0:0/96 — IPv4-mapped IPv6; проверяем вложенный IPv4
            if (bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 0 && bytes[3] == 0
                    && bytes[4] == 0 && bytes[5] == 0 && bytes[6] == 0 && bytes[7] == 0
                    && bytes[8] == 0 && bytes[9] == 0
                    && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
                int v4b0 = bytes[12] & 0xff;
                int v4b1 = bytes[13] & 0xff;
                if (v4b0 == 127) {
                    return true; // loopback
                }
                if (v4b0 == 10) {
                    return true; // 10/8
                }
                if (v4b0 == 172 && (v4b1 >= 16 && v4b1 <= 31)) {
                    return true; // 172.16/12
                }
                if (v4b0 == 192 && v4b1 == 168) {
                    return true; // 192.168/16
                }
                if (v4b0 == 169 && v4b1 == 254) {
                    return true; // link-local
                }
                if (v4b0 == 100 && (v4b1 & 0xc0) == 64) {
                    return true; // CGNAT
                }
            }
        }
        return false;
    }

    /** Публичный адрес, разрешённый для сетевых enrichment-проверок (DNS/TLS). */
    public boolean isPublicAddress(InetAddress address) {
        return address != null && !isPrivateOrSpecial(address);
    }

    private static String resolveNormalizedHost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            String authority = uri.getRawAuthority();
            if (authority == null || authority.isBlank()) {
                authority = uri.getAuthority();
            }
            host = extractHostFromAuthority(authority);
        }
        return normalizeHost(host);
    }

    private static String extractHostFromAuthority(String authority) {
        if (authority == null || authority.isBlank()) {
            return null;
        }
        String value = authority;
        int at = value.lastIndexOf('@');
        if (at >= 0 && at + 1 < value.length()) {
            value = value.substring(at + 1);
        }
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close <= 1) {
                return null;
            }
            return value.substring(1, close);
        }
        int lastColon = value.lastIndexOf(':');
        if (lastColon > 0 && value.indexOf(':') == lastColon) {
            value = value.substring(0, lastColon);
        }
        return value;
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
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record ValidationResult(boolean valid, String host, String errorMessage) {

        public static ValidationResult ok(String host) {
            return new ValidationResult(true, host, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, null, message);
        }
    }

    public record ResolvedHost(boolean valid, String host, List<InetAddress> addresses, String errorMessage) {

        public static ResolvedHost ok(String host, List<InetAddress> addresses) {
            return new ResolvedHost(true, host, List.copyOf(addresses), null);
        }

        public static ResolvedHost invalid(String message) {
            return new ResolvedHost(false, null, List.of(), message);
        }
    }
}
