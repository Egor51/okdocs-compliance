package io.okdocs.compliance.contracts.security;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Синтаксическая нормализация HTTP(S)-адресов, включая IDN-домены.
 *
 * <p>{@link URI#getHost()} возвращает {@code null} для Unicode authority (например,
 * {@code https://домен.рф}), поэтому host извлекается из authority и переводится в ASCII до
 * дальнейших DNS/SSRF-проверок.</p>
 */
public final class HttpUrlNormalizer {

    private static final Pattern ABSOLUTE_SCHEME_PREFIX = Pattern.compile(
            "^[a-z][a-z0-9+.-]*://", Pattern.CASE_INSENSITIVE);

    private HttpUrlNormalizer() {
    }

    /**
     * @param rawUrl исходный URL
     * @param addDefaultHttps добавлять ли {@code https://}, если схема не указана
     * @return URL без fragment, с ASCII-хостом и нормализованным путём
     * @throws IllegalArgumentException если адрес синтаксически некорректен
     */
    public static NormalizedHttpUrl normalize(String rawUrl, boolean addDefaultHttps) {
        String value = rawUrl == null ? "" : rawUrl.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("URL не указан");
        }
        if (addDefaultHttps && !ABSOLUTE_SCHEME_PREFIX.matcher(value).find()) {
            value = "https://" + value;
        }

        URI parsed;
        try {
            parsed = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Некорректный URL", e);
        }

        String scheme = parsed.getScheme() == null
                ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Поддерживаются только http и https");
        }

        Authority authority = parseAuthority(parsed);
        String asciiHost = normalizeHost(authority.host());
        String asciiAuthority = formatAuthority(asciiHost, authority.ipv6(), authority.port());

        StringBuilder normalizedValue = new StringBuilder()
                .append(scheme).append("://").append(asciiAuthority);
        String path = parsed.getRawPath();
        if (path != null && !path.isEmpty()) {
            normalizedValue.append(path);
        }
        String query = parsed.getRawQuery();
        if (query != null) {
            normalizedValue.append('?').append(query);
        }

        try {
            URI normalized = new URI(normalizedValue.toString()).normalize();
            if (normalized.getHost() == null) {
                throw new IllegalArgumentException("Не удалось определить хост из URL");
            }
            return new NormalizedHttpUrl(normalized, normalized.toASCIIString(), asciiHost);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Некорректный URL", e);
        }
    }

    private static Authority parseAuthority(URI uri) {
        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority == null || rawAuthority.isBlank()) {
            throw new IllegalArgumentException("Не удалось определить хост из URL");
        }
        if (uri.getRawUserInfo() != null || rawAuthority.indexOf('@') >= 0) {
            throw new IllegalArgumentException("URL с данными пользователя не поддерживается");
        }

        String parsedHost = uri.getHost();
        if (parsedHost != null && !parsedHost.isBlank()) {
            boolean ipv6 = parsedHost.indexOf(':') >= 0;
            return new Authority(stripIpv6Brackets(parsedHost), uri.getPort(), ipv6);
        }

        if (rawAuthority.startsWith("[")) {
            int closingBracket = rawAuthority.indexOf(']');
            if (closingBracket <= 1) {
                throw new IllegalArgumentException("Некорректный IPv6-адрес");
            }
            String remainder = rawAuthority.substring(closingBracket + 1);
            int port = parsePortSuffix(remainder);
            return new Authority(rawAuthority.substring(1, closingBracket), port, true);
        }

        int colon = rawAuthority.lastIndexOf(':');
        if (colon >= 0) {
            if (rawAuthority.indexOf(':') != colon) {
                throw new IllegalArgumentException("IPv6-адрес должен быть заключён в []");
            }
            String host = rawAuthority.substring(0, colon);
            int port = parsePort(rawAuthority.substring(colon + 1));
            return new Authority(host, port, false);
        }
        return new Authority(rawAuthority, -1, false);
    }

    private static int parsePortSuffix(String suffix) {
        if (suffix.isEmpty()) {
            return -1;
        }
        if (!suffix.startsWith(":")) {
            throw new IllegalArgumentException("Некорректный адрес сайта");
        }
        return parsePort(suffix.substring(1));
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Некорректный порт");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Некорректный порт", e);
        }
    }

    private static String normalizeHost(String host) {
        String value = host == null ? "" : host.trim();
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("Не удалось определить хост из URL");
        }
        if (value.indexOf(':') >= 0) {
            return value.toLowerCase(Locale.ROOT);
        }
        try {
            return IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Некорректное доменное имя", e);
        }
    }

    private static String formatAuthority(String host, boolean ipv6, int port) {
        String authority = ipv6 ? "[" + host + "]" : host;
        return port < 0 ? authority : authority + ":" + port;
    }

    private static String stripIpv6Brackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    public record NormalizedHttpUrl(URI uri, String url, String host) {
    }

    private record Authority(String host, int port, boolean ipv6) {
    }
}
