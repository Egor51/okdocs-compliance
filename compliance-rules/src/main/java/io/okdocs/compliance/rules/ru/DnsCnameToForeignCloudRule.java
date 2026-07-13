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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Домен указывает CNAME на иностранный облачный/CDN/PaaS-провайдер (AWS, Cloudflare, Vercel и т.п.).
 * Это сигнал, что веб-инфраструктура и потенциально хранение данных вынесены к зарубежному провайдеру
 * — риск локализации ПДн. Срабатывает при совпадении CNAME с {@link ForeignCloudDomains}. Категория
 * HOSTING, ч. 5 ст. 18 152-ФЗ. CDN сам по себе не нарушение — нужна ручная проверка размещения БД.
 */
public final class DnsCnameToForeignCloudRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "DNS_CNAME_TO_FOREIGN_CLOUD",
            ScanJurisdiction.RU,
            FindingSeverity.MEDIUM,
            FindingCategory.HOSTING,
            "Домен делегирован иностранному облачному провайдеру (CNAME)",
            "Риск по ч. 5 ст. 18 152-ФЗ при подтверждении хранения ПДн у зарубежного провайдера; "
                    + "до 6 000 000 ₽ для юрлиц.",
            "ч. 5 ст. 18 152-ФЗ (локализация ПДн граждан РФ), ст. 13.11 ч. 8 КоАП РФ",
            "CNAME-запись домена указывает на инфраструктуру иностранного облачного/CDN-провайдера. "
                    + "Это означает, что обслуживание сайта (а возможно и хранение данных) делегировано "
                    + "зарубежной платформе, что создаёт риск нарушения локализации ПДн граждан РФ.",
            "1. Уточните у провайдера фактическое расположение узлов, обрабатывающих российский трафик. "
                    + "2. Убедитесь, что первичное хранение ПДн вынесено на российскую площадку. 3. Рассмотрите "
                    + "российский CDN/хостинг для ресурсов, принимающих ПДн.",
            "Совпадение CNAME с каталогом иностранных облаков не обнаружено",
            "Полученная CNAME-цепочка не совпала с используемым каталогом иностранных облачных провайдеров. Это не подтверждает "
                    + "фактическое расположение origin и баз данных.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        DnsInfo dns = RuleSupport.dnsInfo(ctx);
        if (dns == null || dns.lookupFailed() || dns.cnameChain() == null || dns.cnameChain().isEmpty()) {
            return List.of();
        }
        // cname → matched provider (сохраняем, какой CNAME на какого провайдера указал).
        Map<String, String> matched = new LinkedHashMap<>();
        for (String cname : dns.cnameChain()) {
            String provider = ForeignCloudDomains.matchedProvider(cname);
            if (provider != null) {
                matched.put(cname, provider);
            }
        }
        if (matched.isEmpty()) {
            return List.of();
        }
        String providers = String.join(", ", new java.util.LinkedHashSet<>(matched.values()));
        return List.of(new RuleFact(
                DEFINITION.code(),
                "Домен " + dns.host() + " делегирован иностранному облаку через CNAME: "
                        + String.join(", ", matched.keySet()) + " (провайдеры: " + providers + ").",
                null,
                SourceType.DNS,
                EvidenceType.STATIC_ANALYSIS,
                0.80,
                "dns-cname-foreign-cloud;providers=" + providers,
                VerificationStatus.DETECTED));
    }
}
