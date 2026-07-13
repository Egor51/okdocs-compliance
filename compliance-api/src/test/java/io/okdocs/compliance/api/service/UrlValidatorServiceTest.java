package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorServiceTest {

    private final UrlValidatorService validator = new UrlValidatorService(
            UrlValidatorServiceTest::resolveFixture);

    private static InetAddress[] resolveFixture(String host) throws UnknownHostException {
        return switch (host) {
            case "example.com" -> new InetAddress[]{InetAddress.getByAddress(
                    "example.com", new byte[]{93, (byte) 184, (byte) 216, 34})};
            case "localhost" -> new InetAddress[]{InetAddress.getLoopbackAddress()};
            case "192.168.0.1" -> new InetAddress[]{InetAddress.getByAddress(
                    new byte[]{(byte) 192, (byte) 168, 0, 1})};
            default -> throw new UnknownHostException(host);
        };
    }

    @Test
    void extractsDomainAndNormalizesScheme() {
        var result = validator.validate("example.com/path");
        assertThat(result.domain()).isEqualTo("example.com");
        assertThat(result.normalizedUrl()).startsWith("https://");
    }

    @Test
    void rejectsLoopback() {
        assertThatThrownBy(() -> validator.validate("http://localhost"))
                .isInstanceOf(ComplianceValidationException.class);
    }

    @Test
    void rejectsPrivateIp() {
        assertThatThrownBy(() -> validator.validate("http://192.168.0.1"))
                .isInstanceOf(ComplianceValidationException.class);
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThatThrownBy(() -> validator.validate("ftp://example.com"))
                .isInstanceOf(ComplianceValidationException.class);
    }

    @Test
    void rejectsBlankUrl() {
        assertThatThrownBy(() -> validator.validate("  "))
                .isInstanceOf(ComplianceValidationException.class);
    }

    @Test
    void rejectsUnresolvableHost() {
        assertThatThrownBy(() -> validator.validate("https://this-domain-definitely-does-not-exist-xyzqwerty.invalid"))
                .isInstanceOf(ComplianceValidationException.class);
    }
}
