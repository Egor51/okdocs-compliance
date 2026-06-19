package io.okdocs.compliance.worker.service;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import io.okdocs.compliance.contracts.crawler.DnsInfo;
import io.okdocs.compliance.worker.crawler.UrlValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * DNS-обогащение за ОДИН вызов в enrichment-фазе (Этап 3). Основной A/AAAA-resolve идёт через
 * SSRF-safe {@link UrlValidator#resolvePublicHost(String)}, поэтому {@code resolvedIps} уже прошли
 * блокировку приватных/special адресов и allowlist target-доменов. Один {@link #inspect(String)}
 * собирает A/AAAA + GeoIP по каждому IP, MX и CNAME через JNDI, и отдаёт единый {@link DnsInfo}, из
 * которого worker берёт {@code hostCountry}/{@code resolvedIps} для обратной совместимости с
 * {@code ScanAnalysisContext}.
 * <p>
 * MX/CNAME резолвятся встроенным DNS-провайдером JNDI ({@code com.sun.jndi.dns}) — без внешних
 * зависимостей; MX-хосты для GeoIP резолвятся через safe DNS-resolve без target allowlist, но с той
 * же блокировкой приватных/special IP. Любой сбой не бросает: неразрешимый хост →
 * {@code DnsInfo.lookupFailed=true}, частичные провалы (нет MX/CNAME) → пустые списки.
 * {@code hostCountry}: RU, если хоть один A-адрес в РФ (как раньше), иначе страна первого
 * определённого адреса.
 */
@Slf4j
@Component
public class DnsInspector {

    private final DatabaseReader geoIpDatabaseReader;
    private final UrlValidator urlValidator;

    public DnsInspector(DatabaseReader geoIpDatabaseReader, UrlValidator urlValidator) {
        this.geoIpDatabaseReader = geoIpDatabaseReader;
        this.urlValidator = urlValidator;
    }

    public DnsInfo inspect(String host) {
        if (host == null || host.isBlank()) {
            return failed(host);
        }
        UrlValidator.ResolvedHost resolved = urlValidator.resolvePublicHost(host);
        if (!resolved.valid() || resolved.addresses().isEmpty()) {
            log.debug("DNS: cannot resolve public host {}: {}", host, resolved.errorMessage());
            return failed(host);
        }
        List<InetAddress> addresses = resolved.addresses();

        List<String> ips = new ArrayList<>(addresses.size());
        List<String> ipCountries = new ArrayList<>();
        String hostCountry = null;
        for (InetAddress address : addresses) {
            ips.add(address.getHostAddress());
            String iso = countryOf(address);
            if (iso != null) {
                ipCountries.add(iso);
                if ("RU".equals(iso)) {
                    hostCountry = "RU";
                } else if (hostCountry == null) {
                    hostCountry = iso;
                }
            }
        }

        List<String> cnameChain = lookupRecords(host, "CNAME");
        List<String> nameServers = lookupRecords(host, "NS");
        List<String> mailCountries = mailCountries(host);

        return new DnsInfo(host, false, hostCountry, ips, ipCountries, cnameChain, nameServers, mailCountries);
    }

    /** ISO-страна по IP (GeoIP). null, если адрес не в БД или lookup упал. */
    private String countryOf(InetAddress address) {
        try {
            String iso = geoIpDatabaseReader.country(address).getCountry().getIsoCode();
            return iso == null || iso.isBlank() ? null : iso;
        } catch (AddressNotFoundException e) {
            return null;
        } catch (Exception e) {
            log.debug("GeoIP lookup failed for {}: {}", address.getHostAddress(), e.getMessage());
            return null;
        }
    }

    /** Страны почтовых серверов: MX-хосты → их IP → GeoIP. Уникальные ISO-коды. */
    private List<String> mailCountries(String host) {
        List<String> mxHosts = lookupRecords(host, "MX").stream()
                .map(DnsInspector::mxTarget)
                .filter(h -> h != null && !h.isBlank())
                .toList();
        Set<String> countries = new LinkedHashSet<>();
        for (String mx : mxHosts) {
            UrlValidator.ResolvedHost resolved = urlValidator.resolvePublicDnsHost(mx);
            if (!resolved.valid()) {
                log.debug("DNS: cannot resolve public MX host {}: {}", mx, resolved.errorMessage());
                continue;
            }
            for (InetAddress a : resolved.addresses()) {
                String iso = countryOf(a);
                if (iso != null) {
                    countries.add(iso);
                }
            }
        }
        return new ArrayList<>(countries);
    }

    /**
     * DNS-записи заданного типа через JNDI. Пустой список при отсутствии/ошибке (DNS не должен
     * валить enrichment). Для MX значения вида «10 mail.example.com.», для CNAME/NS — целевые имена.
     */
    private List<String> lookupRecords(String host, String type) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "3000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        InitialDirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes("dns:/" + host, new String[]{type});
            Attribute attr = attrs.get(type);
            if (attr == null) {
                return List.of();
            }
            List<String> values = new ArrayList<>(attr.size());
            for (int i = 0; i < attr.size(); i++) {
                Object v = attr.get(i);
                if (v != null) {
                    values.add(v.toString().trim());
                }
            }
            return values;
        } catch (Exception e) {
            log.debug("DNS {} lookup failed for {}: {}", type, host, e.getMessage());
            return List.of();
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (Exception ignored) {
                    // best-effort close
                }
            }
        }
    }

    /** Из MX-записи «10 mail.example.com.» извлекает хост без приоритета и хвостовой точки. */
    private static String mxTarget(String mxRecord) {
        String[] parts = mxRecord.trim().split("\\s+");
        String target = parts.length >= 2 ? parts[parts.length - 1] : mxRecord.trim();
        target = target.toLowerCase(Locale.ROOT);
        return target.endsWith(".") ? target.substring(0, target.length() - 1) : target;
    }

    private static DnsInfo failed(String host) {
        return new DnsInfo(host, true, null, List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
