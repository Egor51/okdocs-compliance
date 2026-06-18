package io.okdocs.compliance.rules.ru;

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
 * Соединение установлено по устаревшему протоколу (SSLv3 / TLS 1.0 / TLS 1.1). Эти версии имеют
 * известные уязвимости (POODLE, BEAST) и отозваны индустрией; передача ПДн по ним небезопасна.
 * Снимается из {@code TlsInfo.protocol}. Категория SECURITY, ст. 19 152-ФЗ + OWASP.
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

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<RuleFact> facts = new ArrayList<>();
        for (TlsInfo tls : RuleSupport.tlsInfos(ctx)) {
            if (!tls.handshakeOk() || !TlsSupport.isLegacyProtocol(tls.protocol())) {
                continue;
            }
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "Сайт " + tls.host() + " согласует устаревший протокол " + tls.protocol() + ".",
                    "https://" + tls.host(),
                    SourceType.TLS,
                    EvidenceType.STATIC_ANALYSIS,
                    0.95,
                    "tls-legacy-protocol;protocol=" + tls.protocol(),
                    VerificationStatus.DETECTED));
        }
        return facts;
    }
}
