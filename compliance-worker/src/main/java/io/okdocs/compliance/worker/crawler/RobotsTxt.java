package io.okdocs.compliance.worker.crawler;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Минимальный парсер robots.txt (§5.4): только записи {@code User-agent: *} с {@code Disallow}.
 * Best-effort — для MVP достаточно. Перенос из okdocks.
 */
public final class RobotsTxt {

    private final List<String> disallowPrefixes;

    private RobotsTxt(List<String> disallowPrefixes) {
        this.disallowPrefixes = disallowPrefixes;
    }

    public static RobotsTxt allowAll() {
        return new RobotsTxt(List.of());
    }

    public static RobotsTxt parse(String body) {
        if (body == null || body.isBlank()) {
            return allowAll();
        }
        List<String> disallow = new ArrayList<>();
        boolean inStarSection = false;
        for (String rawLine : body.split("\\r?\\n")) {
            String line = rawLine.trim();
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash).trim();
            }
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if ("user-agent".equals(key)) {
                inStarSection = "*".equals(value);
            } else if (inStarSection && "disallow".equals(key) && !value.isEmpty()) {
                disallow.add(value);
            }
        }
        return new RobotsTxt(disallow);
    }

    public boolean isAllowed(String url) {
        if (disallowPrefixes.isEmpty()) {
            return true;
        }
        String path;
        try {
            URI uri = new URI(url);
            path = uri.getRawPath() == null ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) {
                path += "?" + uri.getRawQuery();
            }
        } catch (URISyntaxException e) {
            return true;
        }
        for (String prefix : disallowPrefixes) {
            if ("/".equals(prefix)) {
                return false;
            }
            if (path.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }
}
