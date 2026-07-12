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
import io.okdocs.compliance.persistence.auth.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock AppUserRepository users;
    @Mock PasswordResetTokenRepository tokens;
    @Mock RefreshTokenRepository refreshTokens;
    @Mock PasswordEncoder encoder;
    @Mock RateLimitService rateLimit;
    @Mock MailNotificationService mail;
    @Mock ComplianceMailProperties properties;
    PasswordResetService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(users, tokens, refreshTokens, encoder,
                rateLimit, mail, properties);
    }

    @Test
    void unknownEmailDoesNotRevealAccountAndDoesNotEnqueue() {
        when(users.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());
        service.requestReset(new ForgotPasswordRequest("missing@example.com", "ru"), "1.2.3.4");
        verify(mail, never()).enqueuePasswordReset(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createsHashedTokenAndQueuesEncryptedPayloadInput() {
        when(properties.frontendBaseUrl()).thenReturn("https://app.example");
        AppUser user = activeUser();
        when(users.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(tokens.save(any())).thenAnswer(inv -> {
            PasswordResetToken token = inv.getArgument(0);
            token.setId(UUID.randomUUID());
            return token;
        });

        service.requestReset(new ForgotPasswordRequest("user@example.com", "en"), "1.2.3.4");

        ArgumentCaptor<PasswordResetToken> token = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokens).save(token.capture());
        org.assertj.core.api.Assertions.assertThat(token.getValue().getTokenHash()).hasSize(64);
        verify(mail).enqueuePasswordReset(any(), org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("user@example.com"),
                org.mockito.ArgumentMatchers.contains("/en/reset-password?token="), any(),
                org.mockito.ArgumentMatchers.eq("en"));
    }

    @Test
    void rejectsExpiredToken() {
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(1L);
        token.setExpiresAt(Instant.now().minusSeconds(1));
        when(tokens.findByTokenHash(any())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.reset(new ResetPasswordRequest("raw", "new-password")))
                .isInstanceOf(ComplianceValidationException.class);
        verify(users, never()).save(any());
    }

    private static AppUser activeUser() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setEmail("user@example.com");
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }
}
