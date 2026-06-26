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
 * TLS-рукопожатие не прошло валидацию сертификата (самоподписанный, недоверенный CA, истёкший,
 * битая цепочка). Браузер покажет предупреждение, а ПДн передаются по соединению без доверия. Это
 * именно отдельный {@code TlsInspector}-факт, а не {@code pagesFailed}: при битом TLS обычный fetch
 * падает и страница не доходит до правил — для paid-аудита это должно стать находкой. Сетевые сбои
 * (timeout/refused) → UNVERIFIED, сертификатные → DETECTED. Категория SECURITY, ст. 19 152-ФЗ + OWASP.
 */
public final class TlsCertificateInvalidRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "TLS_CERTIFICATE_INVALID",
            ScanJurisdiction.RU,
            FindingSeverity.HIGH,
            FindingCategory.SECURITY,
            "Недействительный TLS-сертификат",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Transport Layer Security",
            "TLS-рукопожатие с сайтом не прошло проверку сертификата (самоподписанный, недоверенный "
                    + "удостоверяющий центр, истёкший срок или нарушенная цепочка доверия). Браузер выдаёт "
                    + "предупреждение безопасности, а персональные данные передаются по соединению, "
                    + "подлинность которого не подтверждена.",
            "Установите действующий сертификат от доверенного УЦ с полной цепочкой промежуточных "
                    + "сертификатов. Настройте автоматическое продление.",
            "TLS-сертификат действителен",
            "TLS-рукопожатие с сайтом прошло проверку сертификата без ошибок.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<RuleFact> facts = new ArrayList<>();
        for (TlsInfo tls : RuleSupport.tlsInfos(ctx)) {
            if (tls.handshakeOk() && tls.certificateTrusted()) {
                continue;
            }
            boolean certError = !tls.networkError()
                    && (!tls.handshakeOk() || !tls.certificateTrusted()
                    || TlsSupport.isCertError(tls.handshakeError()));
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    evidence(tls, certError),
                    "https://" + tls.host(),
                    SourceType.TLS,
                    EvidenceType.STATIC_ANALYSIS,
                    certError ? 0.95 : 0.50,
                    "tls-handshake-failed;certError=" + certError,
                    certError ? VerificationStatus.DETECTED : VerificationStatus.UNVERIFIED));
        }
        return facts;
    }

    private static String evidence(TlsInfo tls, boolean certError) {
        String error = tls.handshakeError() == null ? "ошибка валидации сертификата" : tls.handshakeError();
        if (tls.handshakeOk() && !tls.certificateTrusted()) {
            return "TLS-сертификат сайта " + tls.host() + " не прошёл проверку доверия: " + error + ".";
        }
        return "TLS-рукопожатие с " + tls.host() + " не удалось: " + error
                + (certError ? "." : ". Возможна сетевая недоступность — требуется ручная проверка.");
    }
}
