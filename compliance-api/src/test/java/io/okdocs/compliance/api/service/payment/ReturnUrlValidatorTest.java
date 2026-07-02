package io.okdocs.compliance.api.service.payment;

import io.okdocs.compliance.api.config.YooKassaProperties;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReturnUrlValidatorTest {

    private final YooKassaProperties properties = new YooKassaProperties(
            null, "shop", "secret", "https://default/return",
            List.of("app.example.com"), false, 1, null);
    private final ReturnUrlValidator validator = new ReturnUrlValidator(properties);

    @Test
    void blankReturnsStoreDefault() {
        assertThat(validator.resolve(null)).isEqualTo("https://default/return");
        assertThat(validator.resolve("  ")).isEqualTo("https://default/return");
    }

    @Test
    void allowlistedHostPasses() {
        assertThat(validator.resolve("https://app.example.com/payment/ok?x=1"))
                .isEqualTo("https://app.example.com/payment/ok?x=1");
    }

    @Test
    void allowlistHostIsCaseInsensitive() {
        assertThat(validator.resolve("https://APP.example.com/ok"))
                .isEqualTo("https://APP.example.com/ok");
    }

    @Test
    void unlistedHostRejected() {
        assertThatThrownBy(() -> validator.resolve("https://evil.com/steal"))
                .isInstanceOf(ComplianceValidationException.class);
    }

    @Test
    void nonHttpSchemeRejected() {
        assertThatThrownBy(() -> validator.resolve("javascript:alert(1)"))
                .isInstanceOf(ComplianceValidationException.class);
        assertThatThrownBy(() -> validator.resolve("ftp://app.example.com/x"))
                .isInstanceOf(ComplianceValidationException.class);
    }

    @Test
    void malformedUrlRejected() {
        assertThatThrownBy(() -> validator.resolve("http://[bad"))
                .isInstanceOf(ComplianceValidationException.class);
    }
}
