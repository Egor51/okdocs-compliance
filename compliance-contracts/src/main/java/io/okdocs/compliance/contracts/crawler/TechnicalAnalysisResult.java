package io.okdocs.compliance.contracts.crawler;

import java.util.List;

/**
 * Site-level техпаспорт скана: HTTP-ответы (headers/redirect-цепочка), TLS, DNS. Наполняется
 * worker'ом в enrichment-фазе и кладётся в {@link ScanAnalysisContext#technical()} ДО RuleEngine —
 * правила безопасности/инфраструктуры остаются чистыми функциями {@code ctx → facts}.
 * <p>
 * Поля nullable/пустые по этапам внедрения: Этап 1 (headers) наполняет только {@code responses};
 * {@code tls}/{@code dns} приходят на Этапах 2-3. Контекст с {@code technical == null} (старые
 * сканы/тесты) — валиден: правила обязаны это переживать и не создавать находок.
 */
public record TechnicalAnalysisResult(
        List<HttpResponseInfo> responses,
        List<TlsInfo> tls,
        DnsInfo dns
) {
    public TechnicalAnalysisResult {
        responses = responses == null ? List.of() : List.copyOf(responses);
        tls = tls == null ? List.of() : List.copyOf(tls);
    }
}
