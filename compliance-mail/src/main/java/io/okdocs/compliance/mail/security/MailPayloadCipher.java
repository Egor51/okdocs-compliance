package io.okdocs.compliance.mail.security;

import io.okdocs.compliance.mail.config.ComplianceMailProperties;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class MailPayloadCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String DISABLED_LOCAL_KEY = "okdocs-mail-disabled-local-payload-key";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public MailPayloadCipher(ComplianceMailProperties properties) {
        String configured = properties.payloadEncryptionKey();
        String material = configured == null || configured.isBlank() ? DISABLED_LOCAL_KEY : configured;
        this.key = new SecretKeySpec(sha256(material), "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encrypt mail payload", e);
        }
    }

    public String decrypt(String payload) {
        try {
            byte[] packed = Base64.getDecoder().decode(payload);
            if (packed.length <= IV_LENGTH) throw new IllegalArgumentException("Invalid encrypted payload");
            byte[] iv = java.util.Arrays.copyOfRange(packed, 0, IV_LENGTH);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, IV_LENGTH, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to decrypt mail payload", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
