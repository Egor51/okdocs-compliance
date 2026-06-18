package io.okdocs.compliance.contracts.enums;

/** Источник, в котором обнаружено доказательство. */
public enum SourceType {
    HTML,
    INLINE_SCRIPT,
    /** HTTP-заголовки ответа (security headers, CORS, кэширование, раскрытие стека). */
    HTTP_HEADER,
    /** TLS-рукопожатие: сертификат, протокол, cipher suite. */
    TLS,
    /** DNS-записи и GeoIP по разрешённым адресам. */
    DNS
}
