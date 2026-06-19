package io.okdocs.compliance.rules.ru;

import java.util.List;
import java.util.Locale;

/**
 * Утилиты TLS-правил Этапа 2: матчинг hostname против SAN/CN (с wildcard), распознавание устаревших
 * протоколов. Чистые функции над данными {@code TlsInfo}, без I/O — TLS-сокет снимает TlsInspector
 * в worker, правила лишь интерпретируют снимок.
 */
final class TlsSupport {

    /** Порог «сертификат скоро истекает» (дней до notAfter). */
    static final long EXPIRES_SOON_DAYS = 30;

    private TlsSupport() {
    }

    /** Совпадает ли host хотя бы с одним именем из SAN/CN (с поддержкой левого wildcard *.example.com). */
    static boolean hostMatchesAny(String host, List<String> names) {
        if (host == null || names == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String name : names) {
            if (name != null && hostMatches(h, name.toLowerCase(Locale.ROOT).trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hostMatches(String host, String name) {
        if (name.isBlank()) {
            return false;
        }
        if (name.startsWith("*.")) {
            // Wildcard покрывает РОВНО один уровень слева: *.example.com ⊇ a.example.com,
            // но НЕ a.b.example.com и не сам example.com.
            String suffix = name.substring(1); // ".example.com"
            int firstDot = host.indexOf('.');
            return firstDot > 0 && host.substring(firstDot).equals(suffix);
        }
        return host.equals(name);
    }

    /**
     * Похожа ли ошибка handshake на проблему сертификата/валидации (а не сети). Сертификатная →
     * правило даёт DETECTED (это реальная проблема сайта), сетевая (timeout/refused) → UNVERIFIED
     * (не удалось проверить, не наказываем за недоступность в момент скана).
     */
    static boolean isCertError(String handshakeError) {
        if (handshakeError == null) {
            return false;
        }
        String e = handshakeError.toLowerCase(Locale.ROOT);
        return e.contains("certificate") || e.contains("cert")
                || e.contains("pkix") || e.contains("validator")
                || e.contains("trust") || e.contains("unable to find valid certification")
                || e.contains("expired") || e.contains("revoked")
                || e.contains("self-signed") || e.contains("self signed")
                || e.contains("handshake_failure") || e.contains("no subject alternative");
    }

    /** Устаревший протокол: всё ниже TLS 1.2 (SSLv3, TLS 1.0, TLS 1.1). */
    static boolean isLegacyProtocol(String protocol) {
        if (protocol == null) {
            return false;
        }
        String p = protocol.toUpperCase(Locale.ROOT).replace(" ", "");
        return p.equals("SSLV3") || p.equals("SSLV2")
                || p.equals("TLSV1") || p.equals("TLSV1.0") || p.equals("TLSV1.1");
    }
}
