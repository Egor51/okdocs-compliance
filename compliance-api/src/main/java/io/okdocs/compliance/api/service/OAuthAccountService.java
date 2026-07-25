package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.contracts.auth.OAuthUserInfo;
import io.okdocs.compliance.contracts.enums.UserPlan;
import io.okdocs.compliance.contracts.enums.UserRole;
import io.okdocs.compliance.contracts.enums.UserStatus;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import io.okdocs.compliance.persistence.auth.OAuthIdentity;
import io.okdocs.compliance.persistence.auth.OAuthIdentityRepository;
import io.okdocs.compliance.mail.notification.MailNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * Резолв локального аккаунта из OAuth-профиля (F.2 §F9). Источник правды связки — таблица
 * {@code oauth_identities} (provider + providerUserId), НЕ email.
 * <p>
 * Три исхода {@link #resolveOrCreate(OAuthUserInfo)}:
 * <ol>
 *   <li><b>Существующая связка</b> — повторный вход тем же соц-аккаунтом → возвращаем её юзера.</li>
 *   <li><b>Auto-link</b> к существующему локальному аккаунту по email — <b>ТОЛЬКО если провайдер
 *       пометил email подтверждённым</b> ({@code emailVerified}). Иначе атакующий, заведя у
 *       провайдера аккаунт с чужим неподтверждённым email, захватил бы локальный аккаунт жертвы.</li>
 *   <li><b>Новый аккаунт</b> — OAuth-only (без пароля, план FREE, баланс 0), плюс новая связка.</li>
 * </ol>
 * Несовпадение email при существующей связке (юзер сменил email у провайдера) не реассайнит
 * аккаунт — связка по providerUserId стабильна.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthAccountService {

    private final AppUserRepository userRepository;
    private final OAuthIdentityRepository identityRepository;
    private final ScanBalanceService balanceService;
    private final ComplianceApiProperties properties;
    private final MailNotificationService mailNotificationService;

    /** Найти/создать аккаунт по OAuth-профилю. Возвращает локального юзера для выдачи токенов. */
    @Transactional
    public AppUser resolveOrCreate(OAuthUserInfo info) {
        return resolveOrCreate(info, "ru");
    }

    @Transactional
    public AppUser resolveOrCreate(OAuthUserInfo info, String locale) {
        // 1. Уже видели эту внешнюю личность — просто логиним её юзера.
        var existing = identityRepository.findByProviderAndProviderUserId(
                info.provider(), info.providerUserId());
        if (existing.isPresent()) {
            return loadActiveUser(existing.get().getUserId());
        }

        // 2. Безопасная auto-link: только при подтверждённом провайдером email и наличии локального
        //    аккаунта с таким email. Неподтверждённый email НЕ линкуем (account takeover).
        if (info.emailVerified() && info.email() != null && !info.email().isBlank()) {
            var localByEmail = userRepository.findByEmailIgnoreCase(info.email());
            if (localByEmail.isPresent()) {
                AppUser user = ensureActive(localByEmail.get());
                linkIdentity(user.getId(), info);
                log.info("OAuth auto-link: provider={} привязан к существующему userId={} по verified email",
                        info.provider(), user.getId());
                return user;
            }
        }

        // 3. Новый OAuth-only аккаунт (без пароля) + баланс + связка.
        AppUser user = createOAuthUser(info, locale);
        linkIdentity(user.getId(), info);
        log.info("OAuth новый аккаунт userId={} provider={}", user.getId(), info.provider());
        return user;
    }

    private AppUser createOAuthUser(OAuthUserInfo info, String locale) {
        AppUser user = new AppUser();
        // app_users.email получает email ТОЛЬКО при emailVerified (P2): иначе атакующий с
        // неподтверждённым чужим email завёл бы локальный аккаунт с заявленным чужим адресом
        // (даже без auto-link). Неподтверждённый/отсутствующий email живёт лишь в oauth_identities.
        String localEmail = info.emailVerified() && info.email() != null && !info.email().isBlank()
                ? info.email().toLowerCase()
                : null;
        user.setEmail(localEmail);
        user.setPasswordHash(null); // OAuth-only: пароля нет, пока юзер его не задаст (F.2)
        user.setName(info.name());
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        user.setPlan(UserPlan.FREE);
        // FREE не имеет платного периода: planRenewsAt — конец оплаченного тарифа, ставится только
        // при покупке PRO/BUSINESS (docs/PLAN-payments.md, Этап 2). Для FREE остаётся null.
        user = userRepository.save(user);

        int quota = properties.plan().quotaFor(UserPlan.FREE);
        balanceService.createForNewUser(user.getId(), quota);
        //test marketing flow: add 1 scan for new user
        balanceService.purchase(user.getId(), 1);
        mailNotificationService.enqueueWelcome(user.getId(), user.getEmail(), user.getName(), locale);
        return user;
    }

    private void linkIdentity(Long userId, OAuthUserInfo info) {
        OAuthIdentity identity = new OAuthIdentity();
        identity.setUserId(userId);
        identity.setProvider(info.provider());
        identity.setProviderUserId(info.providerUserId());
        identity.setEmail(info.email());
        identity.setEmailVerified(info.emailVerified());
        identityRepository.save(identity);
    }

    private AppUser loadActiveUser(Long userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ComplianceValidationException("Пользователь не найден"));
        return ensureActive(user);
    }

    private AppUser ensureActive(AppUser user) {
        if (user.getStatus() == UserStatus.BLOCKED || user.getStatus() == UserStatus.DELETED) {
            throw new ComplianceValidationException("Учётная запись недоступна");
        }
        return user;
    }
}
