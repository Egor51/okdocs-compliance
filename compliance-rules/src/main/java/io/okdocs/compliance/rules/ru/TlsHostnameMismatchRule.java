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
 * Сертификат предъявлен для другого имени: host скана не входит в SAN/CN сертификата (с учётом
 * wildcard). Браузер выдаст ошибку имени, доверие к каналу с ПДн нарушено. Оценивается только при
 * успешном handshake с непустым списком SAN (иначе невалидность ловит {@link TlsCertificateInvalidRule}).
 * Категория SECURITY, ст. 19 152-ФЗ + OWASP.
 */
public final class TlsHostnameMismatchRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "TLS_HOSTNAME_MISMATCH",
            ScanJurisdiction.RU,
            FindingSeverity.HIGH,
            FindingCategory.SECURITY,
            "TLS-сертификат выдан для другого имени",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Transport Layer Security",
            "Имя сайта не входит в Subject Alternative Names предъявленного сертификата. Браузер "
                    + "покажет ошибку несоответствия имени, и пользователь не сможет доверять соединению, "
                    + "по которому передаются персональные данные.",
            "Выпустите сертификат, покрывающий используемое доменное имя (и нужные поддомены), и "
                    + "привяжите его к этому хосту.",
            "Имя сайта соответствует сертификату",
            "Имя проверенного сайта входит в Subject Alternative Names сертификата.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<RuleFact> facts = new ArrayList<>();
        for (TlsInfo tls : RuleSupport.tlsInfos(ctx)) {
            // Только успешный trusted handshake: chain/trust проблемы ловит TLS_CERTIFICATE_INVALID.
            if (!tls.handshakeOk() || !tls.certificateTrusted()) {
                continue;
            }
            if (!tls.hostnameMatched()) {
                String names = tls.subjectAltNames() == null || tls.subjectAltNames().isEmpty()
                        ? (tls.subject() == null ? "сертификат не содержит DNS-имён" : tls.subject())
                        : String.join(", ", tls.subjectAltNames());
                facts.add(new RuleFact(
                        DEFINITION.code(),
                        "Сертификат сайта " + tls.host() + " выдан для других имён: "
                                + names + ".",
                        "https://" + tls.host(),
                        SourceType.TLS,
                        EvidenceType.STATIC_ANALYSIS,
                        0.95,
                        "tls-hostname-mismatch;names=" + names,
                        VerificationStatus.DETECTED));
            }
        }
        return facts;
    }
}
