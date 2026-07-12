package io.okdocs.compliance.mail.security;

import io.okdocs.compliance.mail.config.ComplianceMailProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailPayloadCipherTest {

    @Test
    void encryptsWithRandomIvAndDecrypts() {
        MailPayloadCipher cipher = new MailPayloadCipher(properties(false, "secret-one"));
        String first = cipher.encrypt("{\"token\":\"secret\"}");
        String second = cipher.encrypt("{\"token\":\"secret\"}");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("{\"token\":\"secret\"}");
        assertThat(first).doesNotContain("secret");
    }

    @Test
    void differentKeyCannotDecrypt() {
        String payload = new MailPayloadCipher(properties(false, "secret-one")).encrypt("payload");
        assertThatThrownBy(() -> new MailPayloadCipher(properties(false, "secret-two")).decrypt(payload))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enabledMailRequiresEncryptionKey() {
        assertThatThrownBy(() -> properties(true, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ComplianceMailProperties properties(boolean enabled, String key) {
        return new ComplianceMailProperties(enabled, null, null, null, key, null,
                null, null, null, null, null);
    }
}
