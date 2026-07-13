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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Веб-инфраструктура (A/AAAA-адреса) размещена за пределами РФ. Детализирует общий вердикт
 * {@code HOSTING_OUTSIDE_RU_DETECTED} (тот даёт страну хостинга одним словом): здесь — конкретные
 * зарубежные IP и их страны как доказательная база DNS-анализа, для premium-техпаспорта. Срабатывает,
 * если среди разрешённых адресов есть хоть один не-RU. Категория HOSTING, ч. 5 ст. 18 152-ФЗ.
 */
public final class DnsForeignWebInfrastructureRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "DNS_FOREIGN_WEB_INFRASTRUCTURE",
            ScanJurisdiction.RU,
            // LOW (не MEDIUM): тот же факт «сервер вне РФ», что HOSTING_OUTSIDE_RU_DETECTED (HIGH),
            // даёт основной штраф. Это правило — детализация (конкретные IP/страны для техпаспорта),
            // поэтому минимальный score-вклад, чтобы не штрафовать дважды за один риск.
            FindingSeverity.LOW,
            FindingCategory.HOSTING,
            "Веб-адреса домена ведут на зарубежную инфраструктуру",
            "1 000 000 – 6 000 000 ₽ для юрлиц при подтверждении нарушения локализации; при повторном "
                    + "нарушении до 18 000 000 ₽",
            "ч. 5 ст. 18 152-ФЗ (локализация ПДн граждан РФ), ст. 13.11 ч. 8 КоАП РФ",
            "DNS-резолв домена вернул IP-адреса, расположенные за пределами РФ. Если на сайте "
                    + "обрабатываются ПДн граждан РФ, размещение веб-инфраструктуры за рубежом создаёт риск "
                    + "нарушения требования локализации. Для квалификации нужна проверка фактического "
                    + "расположения базы данных (CDN/прокси сами по себе нарушением не являются).",
            "1. Убедитесь, что первичное хранение ПДн граждан РФ происходит на серверах в России. "
                    + "2. Проверьте, не является ли зарубежный IP лишь CDN/прокси перед российским origin. "
                    + "3. Зафиксируйте расположение БД с ПДн в договорах с провайдерами.",
            "DNS-резолв не вернул IP с GeoIP за пределами РФ",
            "Полученные IP-адреса домена GeoIP-база отнесла к RU. CDN, reverse proxy и фактическое расположение баз данных "
                    + "этой проверкой не подтверждаются.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        DnsInfo dns = RuleSupport.dnsInfo(ctx);
        if (dns == null || dns.lookupFailed() || dns.ipCountries() == null || dns.ipCountries().isEmpty()) {
            return List.of();
        }
        Set<String> foreign = new LinkedHashSet<>();
        for (String iso : dns.ipCountries()) {
            if (iso != null && !"RU".equalsIgnoreCase(iso)) {
                foreign.add(iso.toUpperCase(Locale.ROOT));
            }
        }
        if (foreign.isEmpty()) {
            return List.of();
        }
        List<RuleFact> facts = new ArrayList<>();
        facts.add(new RuleFact(
                DEFINITION.code(),
                "DNS-адреса домена " + dns.host() + " размещены за рубежом (страны: "
                        + String.join(", ", foreign) + "; IP: " + String.join(", ", dns.resolvedIps()) + ").",
                null,
                SourceType.DNS,
                EvidenceType.STATIC_ANALYSIS,
                0.80,
                "dns-foreign-web;countries=" + String.join(",", foreign),
                VerificationStatus.DETECTED));
        return facts;
    }
}
