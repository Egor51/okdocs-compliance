package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.security.JwtService;
import io.okdocs.compliance.contracts.auth.AuthResponse;
import io.okdocs.compliance.contracts.auth.ChangePasswordRequest;
import io.okdocs.compliance.contracts.auth.GuestAuthResponse;
import io.okdocs.compliance.contracts.auth.LoginRequest;
import io.okdocs.compliance.contracts.auth.RegisterRequest;
import io.okdocs.compliance.contracts.auth.UserProfileDto;
import io.okdocs.compliance.contracts.enums.UserPlan;
import io.okdocs.compliance.contracts.enums.UserRole;
import io.okdocs.compliance.contracts.enums.UserStatus;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import io.okdocs.compliance.persistence.auth.RefreshToken;
import io.okdocs.compliance.persistence.auth.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Аутентификация (§4.2): guest-токены, регистрация/логин/refresh/logout, смена пароля.
 * Refresh-токен хранится как hash (не plain). Гостевые сканы при регистрации НЕ привязываются (§2.2).
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String BEARER = "Bearer";

    private final AppUserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ScanBalanceService balanceService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final ComplianceApiProperties properties;
    private final OAuthLoginCodeService oauthLoginCodeService;

    private final SecureRandom secureRandom = new SecureRandom();

    /** Выдать гостевой JWT (анонимный сеанс, регистрация не нужна). */
    public GuestAuthResponse issueGuestToken() {
        UUID guestId = UUID.randomUUID();
        String token = jwtService.issueGuestToken(guestId);
        return new GuestAuthResponse(token, BEARER, jwtService.guestTokenTtlSeconds(), guestId);
    }

    /** Регистрация: создаёт юзера (роль USER, план FREE) + баланс с месячной квотой. */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new ComplianceValidationException("Пользователь с таким email уже существует");
        }
        if (!Boolean.TRUE.equals(request.consentToProcessing())) {
            throw new ComplianceValidationException("Требуется согласие на обработку персональных данных");
        }

        AppUser user = new AppUser();
        user.setEmail(request.email().toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setName(request.name());
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        user.setPlan(UserPlan.FREE);
        // FREE без платного периода: planRenewsAt ставится только при покупке PRO/BUSINESS
        // (docs/PLAN-payments.md, Этап 2).
        user = userRepository.save(user);

        int quota = properties.plan().quotaFor(UserPlan.FREE);
        balanceService.createForNewUser(user.getId(), quota);

        return issueTokensFor(user, null, null);
    }

    /** Логин по email/паролю. */
    @Transactional
    public AuthResponse login(LoginRequest request, String userAgent, String ipAddress) {
        AppUser user = userRepository.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new ComplianceValidationException("Неверный email или пароль"));
        // OAuth-only аккаунт (F.2): пароля нет → password-login невозможен. Осмысленный ответ,
        // а не падение на matches(null). Не сливаем «email существует»: тот же текст и для
        // несуществующего email выше дал бы «Неверный email или пароль» — здесь подсказываем путь,
        // т.к. владелец сам зарегался через соц-сеть и должен знать, как войти.
        if (user.getPasswordHash() == null) {
            throw new ComplianceValidationException(
                    "Для этого аккаунта пароль не задан — войдите через соц-сеть или задайте пароль");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ComplianceValidationException("Неверный email или пароль");
        }
        ensureActive(user);
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
        return issueTokensFor(user, userAgent, ipAddress);
    }

    /**
     * Обмен one-time кода из OAuth-redirect (F.8) на пару токенов. Код одноразовый — повторный обмен
     * отвергается {@link OAuthLoginCodeService}. Невалидный/просроченный/использованный код → ошибка
     * (фронт показывает «войдите для активации»).
     */
    @Transactional
    public AuthResponse exchangeOAuthCode(String code, String userAgent, String ipAddress) {
        Long userId = oauthLoginCodeService.redeem(code);
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ComplianceValidationException("Пользователь не найден"));
        return issueTokensForUser(user, userAgent, ipAddress);
    }

    /** Обновить access-токен по refresh-токену (с ротацией: старый отзывается). */
    @Transactional
    public AuthResponse refresh(String refreshTokenValue, String userAgent, String ipAddress) {
        String hash = hash(refreshTokenValue);
        RefreshToken stored = refreshTokenRepository.findByTokenHashAndRevokedFalse(hash)
                .orElseThrow(() -> new ComplianceValidationException("Невалидный refresh-токен"));
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new ComplianceValidationException("Refresh-токен просрочен");
        }
        revoke(stored);
        AppUser user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new ComplianceValidationException("Пользователь не найден"));
        ensureActive(user);
        return issueTokensFor(user, userAgent, ipAddress);
    }

    /** Отозвать refresh-токен (logout). Идемпотентно: неизвестный токен молча игнорируется. */
    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByTokenHashAndRevokedFalse(hash(refreshTokenValue))
                .ifPresent(this::revoke);
    }

    /** Смена пароля: проверка старого, обновление hash, отзыв всех refresh-токенов. */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ComplianceValidationException("Пользователь не найден"));
        // OAuth-only аккаунт без пароля: change (old→new) неприменим. Set-пароля — отдельный
        // флоу (F.4 D18); здесь не падаем на matches(null), а сообщаем осмысленно.
        if (user.getPasswordHash() == null) {
            throw new ComplianceValidationException(
                    "Пароль ещё не задан — используйте установку пароля, а не смену");
        }
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new ComplianceValidationException("Старый пароль неверен");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        refreshTokenRepository.findByUserIdAndRevokedFalse(userId).forEach(this::revoke);
    }

    /**
     * Выпустить пару токенов для уже резолвленного юзера (OAuth-флоу, F.8) — аккаунт найден/создан
     * в {@code OAuthAccountService}, пароль не проверяется (доказательство владения — сам OAuth).
     * Статус сверяется здесь (а не у вызывающего): если аккаунт заблокировали/удалили после issue
     * one-time кода, токены не выдаём.
     */
    @Transactional
    public AuthResponse issueTokensForUser(AppUser user, String userAgent, String ipAddress) {
        ensureActive(user);
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
        return issueTokensFor(user, userAgent, ipAddress);
    }

    /** Единая проверка доступности аккаунта перед выдачей токенов (login/refresh/OAuth-exchange). */
    private static void ensureActive(AppUser user) {
        if (user.getStatus() == UserStatus.BLOCKED || user.getStatus() == UserStatus.DELETED) {
            throw new ComplianceValidationException("Учётная запись недоступна");
        }
    }

    private AuthResponse issueTokensFor(AppUser user, String userAgent, String ipAddress) {
        String accessToken = jwtService.issueUserToken(user.getId(), user.getRole());
        String refreshValue = newRefreshTokenValue();
        persistRefreshToken(user.getId(), refreshValue, userAgent, ipAddress);
        return new AuthResponse(accessToken, refreshValue, BEARER,
                jwtService.accessTokenTtlSeconds(), toProfile(user));
    }

    private void persistRefreshToken(Long userId, String value, String userAgent, String ipAddress) {
        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setTokenHash(hash(value));
        token.setExpiresAt(Instant.now().plus(properties.auth().refreshTokenTtl()));
        token.setRevoked(false);
        token.setUserAgent(userAgent);
        token.setIpAddress(ipAddress);
        refreshTokenRepository.save(token);
    }

    private void revoke(RefreshToken token) {
        token.setRevoked(true);
        token.setRevokedAt(Instant.now());
        refreshTokenRepository.save(token);
    }

    private String newRefreshTokenValue() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    public static UserProfileDto toProfile(AppUser user) {
        return new UserProfileDto(user.getId(), user.getEmail(), user.getName(),
                user.getRole(), user.getStatus());
    }
}
