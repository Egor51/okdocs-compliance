package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.security.JwtService;
import io.okdocs.compliance.contracts.auth.ChangePasswordRequest;
import io.okdocs.compliance.contracts.auth.LoginRequest;
import io.okdocs.compliance.contracts.enums.UserRole;
import io.okdocs.compliance.contracts.enums.UserStatus;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import io.okdocs.compliance.persistence.auth.RefreshToken;
import io.okdocs.compliance.persistence.auth.RefreshTokenRepository;
import io.okdocs.compliance.mail.notification.MailNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты F.2 §F10 — password-флоу для OAuth-only аккаунтов (passwordHash == null) не должен
 * падать на {@code matches(null)}, а давать осмысленный ответ.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private ScanBalanceService balanceService;
    @Mock
    private JwtService jwtService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private ComplianceApiProperties properties;
    @Mock
    private OAuthLoginCodeService oauthLoginCodeService;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private MailNotificationService mailNotificationService;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, refreshTokenRepository, balanceService,
                jwtService, passwordEncoder, properties, oauthLoginCodeService, rateLimitService,
                mailNotificationService);
    }

    @Test
    void loginOnOAuthOnlyAccountGivesMeaningfulErrorNotCrash() {
        AppUser oauthOnly = oauthOnlyUser();
        when(userRepository.findByEmailIgnoreCase("ivan@example.com")).thenReturn(Optional.of(oauthOnly));

        assertThatThrownBy(() -> service.login(
                new LoginRequest("ivan@example.com", "whatever"), null, null))
                .isInstanceOf(ComplianceValidationException.class)
                .hasMessageContaining("соц-сеть");

        // matches не вызывался с null-хэшем (иначе BCrypt бросил бы IllegalArgumentException).
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void changePasswordOnOAuthOnlyAccountGivesMeaningfulErrorNotCrash() {
        AppUser oauthOnly = oauthOnlyUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(oauthOnly));

        assertThatThrownBy(() -> service.changePassword(1L,
                new ChangePasswordRequest("old", "newpassword123")))
                .isInstanceOf(ComplianceValidationException.class)
                .hasMessageContaining("установку пароля");

        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void oauthExchangeRejectsBlockedUser() {
        // P2: статус мог поменяться после issue кода — перед выдачей токенов сверяем доступность.
        AppUser blocked = oauthOnlyUser();
        blocked.setStatus(UserStatus.BLOCKED);
        when(oauthLoginCodeService.redeem("code-1")).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(blocked));

        assertThatThrownBy(() -> service.exchangeOAuthCode("code-1", null, null))
                .isInstanceOf(ComplianceValidationException.class)
                .hasMessageContaining("недоступна");

        verify(jwtService, never()).issueUserToken(any(), any());
    }

    @Test
    void refreshReuseOfRevokedTokenRevokesAllUserSessions() {
        // Повтор УЖЕ отозванного refresh-токена = сигнал кражи → отзываем все активные сессии юзера.
        RefreshToken revoked = new RefreshToken();
        revoked.setUserId(1L);
        revoked.setRevoked(true);
        revoked.setExpiresAt(Instant.now().plusSeconds(3600));
        RefreshToken activeSession = new RefreshToken();
        activeSession.setUserId(1L);
        activeSession.setRevoked(false);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(revoked));
        when(refreshTokenRepository.findByUserIdAndRevokedFalse(1L)).thenReturn(List.of(activeSession));

        assertThatThrownBy(() -> service.refresh("stolen-token", null, null))
                .isInstanceOf(ComplianceValidationException.class)
                .hasMessageContaining("Невалидный refresh-токен");

        assertThat(activeSession.isRevoked()).isTrue();
        verify(refreshTokenRepository).save(activeSession);
        verify(jwtService, never()).issueUserToken(any(), any());
    }

    @Test
    void refreshWithValidTokenStillRotates() {
        // Валидный (не отозванный, не просроченный) токен по-прежнему ротируется без revoke-all.
        RefreshToken valid = new RefreshToken();
        valid.setUserId(1L);
        valid.setRevoked(false);
        valid.setExpiresAt(Instant.now().plusSeconds(3600));
        AppUser user = oauthOnlyUser();
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(valid));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(properties.auth()).thenReturn(new ComplianceApiProperties.Auth(
                "secret", null, null, null));

        service.refresh("valid-token", null, null);

        assertThat(valid.isRevoked()).isTrue(); // старый отозван (ротация)
        verify(refreshTokenRepository, never()).findByUserIdAndRevokedFalse(any());
        verify(jwtService).issueUserToken(1L, UserRole.USER);
    }

    private AppUser oauthOnlyUser() {
        AppUser u = new AppUser();
        u.setId(1L);
        u.setEmail("ivan@example.com");
        u.setPasswordHash(null); // OAuth-only
        u.setStatus(UserStatus.ACTIVE);
        u.setRole(UserRole.USER);
        return u;
    }
}
