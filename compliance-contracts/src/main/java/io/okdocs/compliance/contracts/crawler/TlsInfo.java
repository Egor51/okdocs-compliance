package io.okdocs.compliance.contracts.crawler;

import java.time.Instant;
import java.util.List;

/**
 * Результат осмотра TLS host:443 (Этап 2). Снимается отдельным {@code TlsInspector}, а не из
 * успешного HTML-fetch: при битом сертификате fetch падает и страница не доходит до правил, а для
 * paid-аудита это должно стать finding. Поэтому {@code handshakeOk == false} + {@code handshakeError}
 * — валидное наполненное состояние, а не отсутствие данных.
 * <p>
 * ⏸ Поля-заделы под Этап 2: на Этапе 1 (headers) {@code TechnicalAnalysisResult.tls} пуст.
 */
public record TlsInfo(
        String host,
        /**
         * TLS-соединение установлено и сервер предъявил сертификат. Не означает, что имя хоста
         * совпало с сертификатом: hostname проверяется отдельно в {@link #hostnameMatched()}.
         */
        boolean handshakeOk,
        String handshakeError,
        /** Цепочка сертификата доверена JVM trust store (без проверки hostname). */
        boolean certificateTrusted,
        /** Host скана покрыт SAN/CN сертификата. false при пустом SAN/CN или mismatch. */
        boolean hostnameMatched,
        /** Сбой похож на сетевой/resolution/timeout, а не на проблему сертификата. */
        boolean networkError,
        /** Протокол, по которому фактически согласовано основное соединение (напр. {@code TLSv1.3}). */
        String protocol,
        String cipherSuite,
        String subject,
        String issuer,
        List<String> subjectAltNames,
        Instant notBefore,
        Instant notAfter,
        /**
         * Все версии протокола, которые сервер реально принимает — снимаются активными probe-сокетами
         * по версиям, а не из одного handshake ({@link #protocol()} вернул бы только максимальную).
         * Нужно для детекции «сервер всё ещё принимает TLS 1.0/1.1»: рукопожатие договаривается на
         * TLS 1.3, но legacy при этом может оставаться включённым.
         * <p>
         * {@code null} — зондирование не выполнялось (старые снимки / неуспешный handshake): отличать
         * от пустого/непустого списка обязательно, иначе правило выдаст ложное «современный TLS».
         */
        List<String> supportedProtocols
) {
    public TlsInfo {
        subjectAltNames = subjectAltNames == null ? List.of() : List.copyOf(subjectAltNames);
        supportedProtocols = supportedProtocols == null ? null : List.copyOf(supportedProtocols);
    }

    /**
     * Совместимый конструктор старой формы. Успешный handshake считается trusted, а hostname
     * вычисляется по SAN/CN-списку; неуспешный handshake классифицируется по тексту ошибки.
     */
    public TlsInfo(String host, boolean handshakeOk, String handshakeError,
                   String protocol, String cipherSuite, String subject, String issuer,
                   List<String> subjectAltNames, Instant notBefore, Instant notAfter) {
        this(host, handshakeOk, handshakeError,
                handshakeOk,
                handshakeOk && hostMatchesAny(host, subjectAltNames),
                !handshakeOk && !isCertificateLikeError(handshakeError),
                protocol, cipherSuite, subject, issuer, subjectAltNames, notBefore, notAfter,
                null);
    }

    private static boolean hostMatchesAny(String host, List<String> names) {
        if (host == null || names == null || names.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(java.util.Locale.ROOT);
        for (String name : names) {
            if (name != null && hostMatches(h, name.toLowerCase(java.util.Locale.ROOT).trim())) {
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
            String suffix = name.substring(1);
            int firstDot = host.indexOf('.');
            return firstDot > 0 && host.substring(firstDot).equals(suffix);
        }
        return host.equals(name);
    }

    private static boolean isCertificateLikeError(String error) {
        if (error == null || error.isBlank()) {
            return false;
        }
        String e = error.toLowerCase(java.util.Locale.ROOT);
        return e.contains("certificate") || e.contains("cert")
                || e.contains("pkix") || e.contains("validator")
                || e.contains("trust") || e.contains("unable to find valid certification")
                || e.contains("expired") || e.contains("revoked")
                || e.contains("self-signed") || e.contains("self signed")
                || e.contains("handshake_failure") || e.contains("no subject alternative");
    }
}
