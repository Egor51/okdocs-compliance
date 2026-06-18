package io.okdocs.compliance.contracts.crawler;

import java.util.List;

/**
 * Результат DNS-обогащения за один safe lookup в enrichment-фазе (Этап 3). Объединяет страну
 * хостинга и разрешённые публичные IP в один результат: {@code hostCountry}/{@code resolvedIps} для
 * обратной совместимости с {@code ScanAnalysisContext}, плюс MX/NS/CNAME-сигналы для DNS-правил.
 * <p>
 * {@code lookupFailed} — хост не разрешился: правила выражают это как UNVERIFIED-finding, а не молчат.
 * ⏸ {@code mailCountries}/{@code cnameChain}/{@code nameServers} — заделы под Этап 3; на Этапе 1
 * заполняются только {@code hostCountry}/{@code resolvedIps}/{@code ipCountries}.
 */
public record DnsInfo(
        String host,
        boolean lookupFailed,
        String hostCountry,
        List<String> resolvedIps,
        List<String> ipCountries,
        List<String> cnameChain,
        List<String> nameServers,
        List<String> mailCountries
) {
    public DnsInfo {
        resolvedIps = resolvedIps == null ? List.of() : List.copyOf(resolvedIps);
        ipCountries = ipCountries == null ? List.of() : List.copyOf(ipCountries);
        cnameChain = cnameChain == null ? List.of() : List.copyOf(cnameChain);
        nameServers = nameServers == null ? List.of() : List.copyOf(nameServers);
        mailCountries = mailCountries == null ? List.of() : List.copyOf(mailCountries);
    }
}
