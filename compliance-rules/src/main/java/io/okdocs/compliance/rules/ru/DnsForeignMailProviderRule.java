package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.DnsInfo;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Почтовые серверы домена (MX) расположены за пределами РФ. Через email проходят ПДн (формы обратной
 * связи, регистрация, уведомления), и зарубежный почтовый провайдер означает обработку этих данных вне
 * РФ — риск трансграничной передачи и нарушения локализации. Срабатывает, если среди стран MX есть
 * не-RU. Категория HOSTING, ч. 5 ст. 18 + ст. 12 152-ФЗ (трансграничная передача).
 */
public final class DnsForeignMailProviderRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "DNS_FOREIGN_MAIL_PROVIDER",
            ScanJurisdiction.RU,
            FindingSeverity.MEDIUM,
            FindingCategory.HOSTING,
            "Почтовые серверы домена расположены за рубежом",
            "Риск по ст. 12 152-ФЗ (трансграничная передача) и ч. 5 ст. 18 (локализация). Размер "
                    + "ответственности зависит от квалификации обработки ПДн через email.",
            "ч. 5 ст. 18 152-ФЗ (локализация), ст. 12 152-ФЗ (трансграничная передача ПДн)",
            "MX-записи домена указывают на почтовые серверы вне РФ. Электронная почта часто содержит "
                    + "персональные данные (обращения, регистрационные письма, уведомления), и их обработка "
                    + "зарубежным почтовым провайдером создаёт риск трансграничной передачи без надлежащих "
                    + "оснований и нарушения требования локализации.",
            "1. Оцените, передаются ли ПДн граждан РФ через корпоративную почту. 2. Рассмотрите "
                    + "российского почтового провайдера для адресов, принимающих ПДн. 3. При необходимости "
                    + "трансграничной передачи обеспечьте основания по ст. 12 152-ФЗ.",
            "Почтовые серверы домена в РФ",
            "MX-записи домена указывают на почтовые серверы в пределах РФ (или MX отсутствуют).");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        DnsInfo dns = RuleSupport.dnsInfo(ctx);
        if (dns == null || dns.lookupFailed() || dns.mailCountries() == null || dns.mailCountries().isEmpty()) {
            return List.of();
        }
        Set<String> foreign = new LinkedHashSet<>();
        for (String iso : dns.mailCountries()) {
            if (iso != null && !"RU".equalsIgnoreCase(iso)) {
                foreign.add(iso.toUpperCase(Locale.ROOT));
            }
        }
        if (foreign.isEmpty()) {
            return List.of();
        }
        return List.of(new RuleFact(
                DEFINITION.code(),
                "Почтовые серверы домена " + dns.host() + " расположены за рубежом (страны: "
                        + String.join(", ", foreign) + ").",
                null,
                SourceType.DNS,
                EvidenceType.STATIC_ANALYSIS,
                0.75,
                "dns-foreign-mail;countries=" + String.join(",", foreign),
                VerificationStatus.DETECTED));
    }
}
