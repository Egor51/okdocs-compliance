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
 * A/AAAA-адреса домена распределены по нескольким странам — признак мультирегионального CDN/балансира,
 * где часть инфраструктуры заведомо вне РФ и трафик с ПДн может маршрутизироваться через зарубежные
 * узлы. Срабатывает при ≥2 различных странах среди IP. Категория HOSTING, ч. 5 ст. 18 152-ФЗ.
 */
public final class DnsMulticountryHostingRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "DNS_MULTICOUNTRY_HOSTING",
            ScanJurisdiction.RU,
            FindingSeverity.LOW,
            FindingCategory.HOSTING,
            "Хостинг домена распределён по нескольким странам",
            "Без прямого штрафа: сигнал мультирегиональной инфраструктуры. Влияет на оценку рисков "
                    + "локализации ПДн по ч. 5 ст. 18 152-ФЗ.",
            "ч. 5 ст. 18 152-ФЗ (локализация ПДн), организационная мера контроля инфраструктуры",
            "IP-адреса домена расположены в разных странах. Это типично для мультирегиональных CDN и "
                    + "балансировщиков: часть узлов находится вне РФ, и запросы с персональными данными "
                    + "граждан РФ могут обрабатываться зарубежной инфраструктурой.",
            "Убедитесь, что обработка и первичное хранение ПДн граждан РФ привязаны к российским узлам. "
                    + "Проверьте географическую маршрутизацию CDN и расположение origin-серверов.",
            "Хостинг домена не распределён между странами",
            "Все IP-адреса домена определены в пределах одной страны.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        DnsInfo dns = RuleSupport.dnsInfo(ctx);
        if (dns == null || dns.lookupFailed() || dns.ipCountries() == null) {
            return List.of();
        }
        Set<String> countries = new LinkedHashSet<>();
        for (String iso : dns.ipCountries()) {
            if (iso != null && !iso.isBlank()) {
                countries.add(iso.toUpperCase(Locale.ROOT));
            }
        }
        if (countries.size() < 2) {
            return List.of();
        }
        return List.of(new RuleFact(
                DEFINITION.code(),
                "IP-адреса домена " + dns.host() + " расположены в нескольких странах: "
                        + String.join(", ", countries) + ".",
                null,
                SourceType.DNS,
                EvidenceType.STATIC_ANALYSIS,
                0.75,
                "dns-multicountry;countries=" + String.join(",", countries),
                VerificationStatus.DETECTED));
    }
}
