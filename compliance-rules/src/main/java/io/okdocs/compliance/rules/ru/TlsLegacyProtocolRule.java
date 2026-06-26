package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.rules.common.TlsSupport;

import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.crawler.TlsInfo;
import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleDefinition;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.RuleSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер всё ещё принимает устаревший протокол (SSLv3 / TLS 1.0 / TLS 1.1). Эти версии имеют
 * известные уязвимости (POODLE, BEAST) и отозваны индустрией; передача ПДн по ним небезопасна.
 * <p>
 * Проверяется {@code TlsInfo.supportedProtocols} (активный probe версий в инспекторе), а НЕ
 * {@code protocol()}: рукопожатие договаривается на максимум (TLS 1.3), поэтому по согласованному
 * протоколу включённый параллельно TLS 1.0/1.1 не виден — это давало ложный PASSED. Если probe не
 * выполнялся ({@code supportedProtocols == null}: старый снимок / неуспешный handshake), правило
 * {@code NOT_EVALUATED}, а не PASSED — «не проверяли» ≠ «современный TLS». Категория SECURITY,
 * ст. 19 152-ФЗ + OWASP.
 */
public final class TlsLegacyProtocolRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "TLS_LEGACY_PROTOCOL",
            ScanJurisdiction.RU,
            FindingSeverity.MEDIUM,
            FindingCategory.SECURITY,
            "Используется устаревшая версия TLS",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Transport Layer Security",
            "Сайт согласует соединение по устаревшему протоколу (SSLv3, TLS 1.0 или TLS 1.1) с "
                    + "известными уязвимостями. Передача персональных данных по такому каналу не обеспечивает "
                    + "должного уровня защиты.",
            "Отключите SSLv3/TLS 1.0/1.1 на сервере, оставьте только TLS 1.2 и TLS 1.3.",
            "Используется современная версия TLS",
            "Проверенное соединение согласовано по TLS 1.2 или TLS 1.3.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    /**
     * Применимо, только если хотя бы по одному успешному handshake инспектор зондировал версии
     * ({@code supportedProtocols != null}). Иначе данных о поддержке legacy нет → NOT_EVALUATED,
     * а не PASSED: иначе отчёт выдал бы ложное «современный TLS» там, где просто не проверяли.
     */
    @Override
    public boolean appliesTo(ScanAnalysisContext ctx) {
        for (TlsInfo tls : RuleSupport.tlsInfos(ctx)) {
            if (tls.handshakeOk() && tls.supportedProtocols() != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<RuleFact> facts = new ArrayList<>();
        for (TlsInfo tls : RuleSupport.tlsInfos(ctx)) {
            if (!tls.handshakeOk() || tls.supportedProtocols() == null) {
                continue;
            }
            List<String> legacy = new ArrayList<>();
            for (String protocol : tls.supportedProtocols()) {
                if (TlsSupport.isLegacyProtocol(protocol)) {
                    legacy.add(protocol);
                }
            }
            if (legacy.isEmpty()) {
                continue;
            }
            String joined = String.join(", ", legacy);
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "Сайт " + tls.host() + " принимает устаревший протокол: " + joined + ".",
                    "https://" + tls.host(),
                    SourceType.TLS,
                    EvidenceType.STATIC_ANALYSIS,
                    0.95,
                    "tls-legacy-protocol;protocol=" + String.join(",", legacy),
                    VerificationStatus.DETECTED));
        }
        return facts;
    }
}
