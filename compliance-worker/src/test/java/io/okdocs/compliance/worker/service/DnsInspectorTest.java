package io.okdocs.compliance.worker.service;

import com.maxmind.geoip2.DatabaseReader;
import io.okdocs.compliance.contracts.crawler.DnsInfo;
import io.okdocs.compliance.worker.crawler.UrlValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * DnsInspector не должен бросать: неразрешимый/пустой host → {@link DnsInfo} с {@code lookupFailed=true}.
 * Реальный резолв A/MX/CNAME требует сети и тестируется на интеграционном уровне; здесь — контракт
 * «всегда возвращает DnsInfo, не валит enrichment».
 */
@ExtendWith(MockitoExtension.class)
class DnsInspectorTest {

    @Mock
    DatabaseReader geoIpDatabaseReader;
    @Mock
    UrlValidator urlValidator;

    private DnsInspector inspector() {
        return new DnsInspector(geoIpDatabaseReader, urlValidator);
    }

    @Test
    void blankHostReturnsLookupFailed() {
        DnsInfo info = inspector().inspect("   ");
        assertThat(info.lookupFailed()).isTrue();
        assertThat(info.resolvedIps()).isEmpty();
        assertThat(info.hostCountry()).isNull();
    }

    @Test
    void unresolvableHostReturnsLookupFailed() {
        when(urlValidator.resolvePublicHost("nonexistent-host-12345.invalid-tld-zzz"))
                .thenReturn(UrlValidator.ResolvedHost.invalid("not resolvable"));

        DnsInfo info = inspector().inspect("nonexistent-host-12345.invalid-tld-zzz");
        assertThat(info.host()).isEqualTo("nonexistent-host-12345.invalid-tld-zzz");
        assertThat(info.lookupFailed()).isTrue();
        assertThat(info.mailCountries()).isEmpty();
    }
}
