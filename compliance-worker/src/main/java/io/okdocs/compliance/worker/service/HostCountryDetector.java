package io.okdocs.compliance.worker.service;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Определение страны хостинга по IP (§5.5). Наполняет {@code hostCountry}/{@code resolvedIps}
 * в {@link io.okdocs.compliance.contracts.crawler.ScanAnalysisContext} ДО запуска RuleEngine —
 * правила не зависят от GeoIP напрямую ({@code compliance-rules} без MaxMind).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HostCountryDetector {

    private final DatabaseReader geoIpDatabaseReader;

    /**
     * ISO-код страны хостинга по hostname. Проверяет все A-записи: если хотя бы одна в RU —
     * возвращает {@code "RU"}; иначе код первого успешно определённого адреса. Empty, если хост
     * не резолвится или ни один IP не найден в БД.
     */
    public Optional<String> detectCountry(String host) {
        InetAddress[] addresses = resolve(host);
        if (addresses == null) {
            return Optional.empty();
        }
        String firstFound = null;
        for (InetAddress address : addresses) {
            try {
                String isoCode = geoIpDatabaseReader.country(address).getCountry().getIsoCode();
                if (isoCode == null) {
                    continue;
                }
                if ("RU".equals(isoCode)) {
                    return Optional.of("RU");
                }
                if (firstFound == null) {
                    firstFound = isoCode;
                }
            } catch (AddressNotFoundException e) {
                log.debug("GeoIP: address not found in DB: {}", address.getHostAddress());
            } catch (Exception e) {
                log.warn("GeoIP: lookup failed for {}: {}", address.getHostAddress(), e.getMessage());
            }
        }
        return Optional.ofNullable(firstFound);
    }

    /** Список разрешённых IP хоста (для {@code ScanAnalysisContext.resolvedIps}). */
    public List<String> resolveIps(String host) {
        InetAddress[] addresses = resolve(host);
        if (addresses == null) {
            return List.of();
        }
        List<String> ips = new ArrayList<>(addresses.length);
        for (InetAddress address : addresses) {
            ips.add(address.getHostAddress());
        }
        return ips;
    }

    private InetAddress[] resolve(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            log.debug("GeoIP: cannot resolve host {}", host);
            return null;
        }
    }
}
