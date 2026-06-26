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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * TLS-сертификат скоро истекает ({@code notAfter} в пределах {@link TlsSupport#EXPIRES_SOON_DAYS}
 * дней). Истечение сертификата = недоступность HTTPS и предупреждение браузера на странице с ПДн.
 * Снимается из {@code TlsInfo}, время скана передаётся через {@code ctx} косвенно — сравниваем с
 * {@code Instant.now()} на момент оценки правил. Категория SECURITY, ст. 19 152-ФЗ + OWASP.
 */
public final class TlsCertificateExpiresSoonRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "TLS_CERTIFICATE_EXPIRES_SOON",
            ScanJurisdiction.RU,
            FindingSeverity.MEDIUM,
            FindingCategory.SECURITY,
            "TLS-сертификат скоро истекает",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Transport Layer Security",
            "Срок действия TLS-сертификата истекает в ближайшие " + TlsSupport.EXPIRES_SOON_DAYS
                    + " дней. После истечения HTTPS перестанет работать, браузер заблокирует доступ и "
                    + "предупредит об опасности — передача персональных данных окажется нарушенной.",
            "Настройте автоматическое продление сертификата (ACME/Let's Encrypt) и мониторинг срока "
                    + "действия с запасом не менее 30 дней.",
            "Срок действия TLS-сертификата достаточный",
            "У проверенного сертификата до истечения остаётся более " + TlsSupport.EXPIRES_SOON_DAYS + " дней.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        Instant now = Instant.now();
        List<RuleFact> facts = new ArrayList<>();
        for (TlsInfo tls : RuleSupport.tlsInfos(ctx)) {
            if (!tls.handshakeOk() || tls.notAfter() == null) {
                continue;
            }
            long daysLeft = Duration.between(now, tls.notAfter()).toDays();
            // Уже истёкший сертификат — это TLS_CERTIFICATE_INVALID (handshake упадёт), здесь только
            // «скоро истекает», т.е. ещё валиден, но запас < порога.
            if (daysLeft >= 0 && daysLeft <= TlsSupport.EXPIRES_SOON_DAYS) {
                facts.add(new RuleFact(
                        DEFINITION.code(),
                        "TLS-сертификат " + tls.host() + " истекает через " + daysLeft + " дн. (срок до "
                                + tls.notAfter() + ").",
                        "https://" + tls.host(),
                        SourceType.TLS,
                        EvidenceType.STATIC_ANALYSIS,
                        0.95,
                        "tls-expires-soon;daysLeft=" + daysLeft,
                        VerificationStatus.DETECTED));
            }
        }
        return facts;
    }
}
