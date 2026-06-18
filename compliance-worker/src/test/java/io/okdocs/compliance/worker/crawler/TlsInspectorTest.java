package io.okdocs.compliance.worker.crawler;

import io.okdocs.compliance.contracts.crawler.TlsInfo;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TlsInspector НЕ должен бросать и НЕ должен валить скан: любой сбой (невалидный/неразрешимый host)
 * → {@link TlsInfo} с {@code handshakeOk=false}. Реальный TLS-handshake требует сети и тестируется
 * на интеграционном уровне; здесь — контракт «всегда возвращает TlsInfo».
 */
@ExtendWith(MockitoExtension.class)
class TlsInspectorTest {

    @Mock
    UrlValidator urlValidator;

    private TlsInspector inspector() {
        return new TlsInspector(new ComplianceWorkerProperties(), urlValidator);
    }

    @Test
    void blankHostReturnsFailedTlsInfoWithoutResolving() {
        TlsInfo info = inspector().inspect("  ");
        assertThat(info.handshakeOk()).isFalse();
        assertThat(info.handshakeError()).isNotBlank();
    }

    @Test
    void unresolvableHostReturnsFailedTlsInfo() {
        when(urlValidator.resolvePublicHost("blocked.invalid"))
                .thenReturn(UrlValidator.ResolvedHost.invalid("private/blocked host"));

        TlsInfo info = inspector().inspect("blocked.invalid");

        assertThat(info.host()).isEqualTo("blocked.invalid");
        assertThat(info.handshakeOk()).isFalse();
        // resolvedIps пуст → fallback на SSRF-safe резолв, который вернул invalid → host not resolvable.
        assertThat(info.handshakeError()).isEqualTo("host not resolvable");
        assertThat(info.subjectAltNames()).isEmpty();
    }

    @Test
    void rejectsUnsafeProvidedIpWithoutReResolvingHost() {
        // resolvedIps (из DnsInfo) непуст → TLS НЕ должен заново резолвить host, но обязан
        // отфильтровать unsafe/private IP перед подключением.
        when(urlValidator.isPublicAddress(any())).thenReturn(false);

        TlsInfo info = inspector().inspect("site.ru", java.util.List.of("127.0.0.1"));

        assertThat(info.host()).isEqualTo("site.ru");
        assertThat(info.handshakeOk()).isFalse();
        assertThat(info.networkError()).isTrue();
        verify(urlValidator).isPublicAddress(any());
        verify(urlValidator, never()).resolvePublicHost(any());
    }
}
