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

import java.util.List;

/**
 * DNS-резолв основного домена не удался: невозможно определить инфраструктуру (страну хостинга, MX,
 * CDN). Не молчим, а фиксируем как UNVERIFIED — оператору нужна ручная проверка локализации ПДн.
 * Категория HOSTING, основание — ч. 5 ст. 18 152-ФЗ (косвенно: невозможность подтвердить локализацию).
 */
public final class DnsLookupFailedRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "DNS_LOOKUP_FAILED",
            ScanJurisdiction.RU,
            FindingSeverity.LOW,
            FindingCategory.HOSTING,
            "Не удалось выполнить DNS-резолв домена",
            "Без прямого штрафа: невозможность автоматически подтвердить размещение инфраструктуры. "
                    + "Локализация ПДн (ч. 5 ст. 18 152-ФЗ) требует ручной проверки.",
            "ч. 5 ст. 18 152-ФЗ (локализация ПДн), организационная мера контроля инфраструктуры",
            "DNS-запрос к домену не вернул адресов: автоматическая проверка страны хостинга, почтовых "
                    + "серверов и CDN невозможна. Это не нарушение само по себе, но требование локализации "
                    + "персональных данных не может быть подтверждено автоматически.",
            "Проверьте корректность DNS-записей домена и повторите анализ. Подтвердите расположение "
                    + "серверов и хранилищ ПДн вручную.",
            "DNS-резолв домена выполнен",
            "DNS-запрос к домену успешно вернул адреса для анализа инфраструктуры.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        DnsInfo dns = RuleSupport.dnsInfo(ctx);
        if (dns == null || !dns.lookupFailed()) {
            return List.of();
        }
        return List.of(new RuleFact(
                DEFINITION.code(),
                "DNS-резолв домена " + dns.host() + " не удался: инфраструктуру определить нельзя. "
                        + "Требуется ручная проверка локализации ПДн.",
                null,
                SourceType.DNS,
                EvidenceType.STATIC_ANALYSIS,
                null,
                "dns-lookup-failed;host=" + dns.host(),
                VerificationStatus.UNVERIFIED));
    }
}
