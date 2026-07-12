package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.auth.ForgotPasswordRequest;
import io.okdocs.compliance.contracts.auth.ResetPasswordRequest;
import io.okdocs.compliance.contracts.enums.UserStatus;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.mail.config.ComplianceMailProperties;
import io.okdocs.compliance.mail.notification.MailNotificationService;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import io.okdocs.compliance.persistence.auth.PasswordResetToken;
import io.okdocs.compliance.persistence.auth.PasswordResetTokenRepository;
import io.okdocs.compliance.persistence.auth.RefreshToken;
import io.okdocs.compliance.persistence.auth.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    private final AppUserRepository userRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final RateLimitService rateLimitService;
    private final MailNotificationService mailNotificationService;
    private final ComplianceMailProperties mailProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public void requestReset(ForgotPasswordRequest request, String ipAddress) {
        rateLimitService.checkAuthAttemptAllowed(ipAddress);
        userRepository.findByEmailIgnoreCase(request.email().trim()).ifPresent(user -> {
            if (user.getStatus() != UserStatus.ACTIVE || user.getEmail() == null) return;
            Instant now = Instant.now();
            resetTokenRepository.invalidateUnusedByUserId(user.getId(), now);
            String rawToken = newToken();
            PasswordResetToken token = new PasswordResetToken();
            token.setUserId(user.getId());
            token.setTokenHash(hash(rawToken));
            token.setExpiresAt(now.plus(TOKEN_TTL));
            token.setRequestedIp(ipAddress);
            resetTokenRepository.save(token);

            String locale = normalizeLocale(request.locale());
            String resetUrl = mailProperties.frontendBaseUrl() + "/" + locale
                    + "/reset-password?token=" + rawToken;
            mailNotificationService.enqueuePasswordReset(token.getId(), user.getId(), user.getEmail(),
                    resetUrl, token.getExpiresAt(), locale);
        });
    }

    @Transactional
    public void reset(ResetPasswordRequest request) {
        PasswordResetToken token = resetTokenRepository.findByTokenHash(hash(request.token()))
                .orElseThrow(PasswordResetService::invalidToken);
        Instant now = Instant.now();
        if (token.getUsedAt() != null || !token.getExpiresAt().isAfter(now)) throw invalidToken();

        AppUser user = userRepository.findById(token.getUserId())
                .orElseThrow(PasswordResetService::invalidToken);
        if (user.getStatus() != UserStatus.ACTIVE) throw invalidToken();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        token.setUsedAt(now);
        resetTokenRepository.save(token);
        refreshTokenRepository.findByUserIdAndRevokedFalse(user.getId()).forEach(t -> revoke(t, now));
    }

    private void revoke(RefreshToken token, Instant now) {
        token.setRevoked(true);
        token.setRevokedAt(now);
        refreshTokenRepository.save(token);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String normalizeLocale(String locale) {
        return locale != null && locale.toLowerCase().startsWith("en") ? "en" : "ru";
    }

    private static ComplianceValidationException invalidToken() {
        return new ComplianceValidationException("Ссылка восстановления недействительна или истекла");
    }
}
