package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.security.JwtService;
import io.okdocs.compliance.contracts.auth.AuthResponse;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
    void refreshReuseOutsideGraceRevokesOnlyCurrentTokenFamily() {
        UUID familyId = UUID.randomUUID();
        RefreshToken revoked = new RefreshToken();
        revoked.setId(UUID.randomUUID());
        revoked.setUserId(1L);
        revoked.setFamilyId(familyId);
        revoked.setRevoked(true);
        revoked.setExpiresAt(Instant.now().plusSeconds(3600));
        revoked.setRotationGraceUntil(Instant.now().minusSeconds(1));

        RefreshToken sameFamily = new RefreshToken();
        sameFamily.setUserId(1L);
        sameFamily.setFamilyId(familyId);
        sameFamily.setRevoked(false);
        RefreshToken otherDevice = new RefreshToken();
        otherDevice.setUserId(1L);
        otherDevice.setFamilyId(UUID.randomUUID());
        otherDevice.setRevoked(false);

        when(refreshTokenRepository.findByTokenHashForUpdate(any())).thenReturn(Optional.of(revoked));
        when(refreshTokenRepository.findByFamilyIdAndRevokedFalse(familyId))
                .thenReturn(List.of(sameFamily));

        assertThatThrownBy(() -> service.refresh("stolen-token", null, null))
                .isInstanceOf(ComplianceValidationException.class)
                .hasMessageContaining("Невалидный refresh-токен");

        assertThat(sameFamily.isRevoked()).isTrue();
        assertThat(otherDevice.isRevoked()).isFalse();
        verify(refreshTokenRepository).save(sameFamily);
        verify(refreshTokenRepository, never()).findByUserIdAndRevokedFalse(any());
        verify(jwtService, never()).issueUserToken(any(), any());
    }

    @Test
    void concurrentRefreshInsideGraceReturnsSameSuccessor() throws Exception {
        String secret = "refresh-test-secret";
        UUID familyId = UUID.randomUUID();
        UUID successorId = UUID.randomUUID();
        String successorValue = refreshTokenValue(successorId, secret);

        RefreshToken rotated = new RefreshToken();
        rotated.setId(UUID.randomUUID());
        rotated.setUserId(1L);
        rotated.setFamilyId(familyId);
        rotated.setRevoked(true);
        rotated.setExpiresAt(Instant.now().plusSeconds(3600));
        rotated.setReplacedById(successorId);
        rotated.setRotationGraceUntil(Instant.now().plusSeconds(10));

        RefreshToken successor = new RefreshToken();
        successor.setId(successorId);
        successor.setUserId(1L);
        successor.setFamilyId(familyId);
        successor.setTokenHash(sha256(successorValue));
        successor.setRevoked(false);
        successor.setExpiresAt(Instant.now().plusSeconds(3600));

        when(refreshTokenRepository.findByTokenHashForUpdate(any())).thenReturn(Optional.of(rotated));
        when(refreshTokenRepository.findById(successorId)).thenReturn(Optional.of(successor));
        when(userRepository.findById(1L)).thenReturn(Optional.of(oauthOnlyUser()));
        when(properties.auth()).thenReturn(new ComplianceApiProperties.Auth(secret, null, null, null));

        AuthResponse response = service.refresh("old-token-from-parallel-request", null, null);

        assertThat(response.refreshToken()).isEqualTo(successorValue);
        assertThat(successor.isRevoked()).isFalse();
        verify(refreshTokenRepository, never()).findByFamilyIdAndRevokedFalse(any());
        verify(refreshTokenRepository, never()).save(any());
        verify(jwtService).issueUserToken(1L, UserRole.USER);
    }

    @Test
    void logoutWithRotatedTokenRevokesOnlyItsActiveFamily() {
        UUID familyId = UUID.randomUUID();
        RefreshToken oldToken = new RefreshToken();
        oldToken.setFamilyId(familyId);
        oldToken.setRevoked(true);
        RefreshToken activeSuccessor = new RefreshToken();
        activeSuccessor.setFamilyId(familyId);
        activeSuccessor.setRevoked(false);
        RefreshToken otherDevice = new RefreshToken();
        otherDevice.setFamilyId(UUID.randomUUID());
        otherDevice.setRevoked(false);

        when(refreshTokenRepository.findByTokenHashForUpdate(any()))
                .thenReturn(Optional.of(oldToken));
        when(refreshTokenRepository.findByFamilyIdAndRevokedFalse(familyId))
                .thenReturn(List.of(activeSuccessor));

        service.logout("old-token");

        assertThat(activeSuccessor.isRevoked()).isTrue();
        assertThat(otherDevice.isRevoked()).isFalse();
        verify(refreshTokenRepository).save(activeSuccessor);
    }

    @Test
    void refreshWithValidTokenStillRotates() {
        // Валидный (не отозванный, не просроченный) токен по-прежнему ротируется без revoke-all.
        RefreshToken valid = new RefreshToken();
        UUID familyId = UUID.randomUUID();
        valid.setId(UUID.randomUUID());
        valid.setUserId(1L);
        valid.setFamilyId(familyId);
        valid.setRevoked(false);
        valid.setExpiresAt(Instant.now().plusSeconds(3600));
        AppUser user = oauthOnlyUser();
        when(refreshTokenRepository.findByTokenHashForUpdate(any())).thenReturn(Optional.of(valid));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(properties.auth()).thenReturn(new ComplianceApiProperties.Auth(
                "secret", null, null, null));

        service.refresh("valid-token", null, null);

        assertThat(valid.isRevoked()).isTrue(); // старый отозван (ротация)
        assertThat(valid.getReplacedById()).isNotNull();
        assertThat(valid.getRotationGraceUntil()).isAfter(Instant.now());
        verify(refreshTokenRepository).save(argThat(token -> token != valid
                && familyId.equals(token.getFamilyId())
                && valid.getReplacedById().equals(token.getId())
                && !token.isRevoked()));
        verify(refreshTokenRepository, never()).findByUserIdAndRevokedFalse(any());
        verify(jwtService).issueUserToken(1L, UserRole.USER);
    }

    private static String refreshTokenValue(UUID tokenId, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(
                ("okdocs-refresh-token:" + tokenId).getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
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
