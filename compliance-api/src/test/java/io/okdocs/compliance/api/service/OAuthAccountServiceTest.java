package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.contracts.auth.OAuthUserInfo;
import io.okdocs.compliance.contracts.enums.OAuthProvider;
import io.okdocs.compliance.contracts.enums.UserPlan;
import io.okdocs.compliance.contracts.enums.UserRole;
import io.okdocs.compliance.contracts.enums.UserStatus;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.persistence.auth.AppUser;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import io.okdocs.compliance.persistence.auth.OAuthIdentity;
import io.okdocs.compliance.persistence.auth.OAuthIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthAccountServiceTest {

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private OAuthIdentityRepository identityRepository;
    @Mock
    private ScanBalanceService balanceService;

    private OAuthAccountService service;

    @BeforeEach
    void setUp() {
        // Реальные дефолты properties: quotaFor(FREE)=0 после F.1.
        ComplianceApiProperties props = new ComplianceApiProperties(
                null, null, null, new ComplianceApiProperties.Plan(null), null, null, null, null, null);
        service = new OAuthAccountService(userRepository, identityRepository, balanceService, props);
    }

    /** Стаб save() для тестов, создающих нового юзера: возвращает его с проставленным id=42. */
    private void stubUserSaveAssignsId() {
        when(userRepository.save(any(AppUser.class))).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(42L);
            }
            return u;
        });
    }

    private OAuthUserInfo info(String email, boolean verified) {
        return new OAuthUserInfo(OAuthProvider.GOOGLE, "ext-123", email, verified, "Ivan");
    }

    @Test
    void returningIdentityLogsInExistingUserWithoutCreating() {
        OAuthIdentity identity = new OAuthIdentity();
        identity.setUserId(7L);
        when(identityRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "ext-123"))
                .thenReturn(Optional.of(identity));
        AppUser existing = activeUser(7L, "ivan@example.com");
        when(userRepository.findById(7L)).thenReturn(Optional.of(existing));

        AppUser result = service.resolveOrCreate(info("ivan@example.com", true));

        assertThat(result.getId()).isEqualTo(7L);
        verify(userRepository, never()).save(any());
        verify(identityRepository, never()).save(any());
        verify(balanceService, never()).createForNewUser(anyLong(), anyInt());
    }

    @Test
    void autoLinksToExistingAccountWhenEmailVerified() {
        when(identityRepository.findByProviderAndProviderUserId(any(), any())).thenReturn(Optional.empty());
        AppUser local = activeUser(9L, "ivan@example.com");
        when(userRepository.findByEmailIgnoreCase("ivan@example.com")).thenReturn(Optional.of(local));

        AppUser result = service.resolveOrCreate(info("ivan@example.com", true));

        assertThat(result.getId()).isEqualTo(9L);
        // Связку создали, нового юзера/баланс — нет.
        ArgumentCaptor<OAuthIdentity> link = ArgumentCaptor.forClass(OAuthIdentity.class);
        verify(identityRepository).save(link.capture());
        assertThat(link.getValue().getUserId()).isEqualTo(9L);
        verify(userRepository, never()).save(any());
        verify(balanceService, never()).createForNewUser(anyLong(), anyInt());
    }

    @Test
    void doesNotAutoLinkWhenEmailUnverified_createsSeparateAccount() {
        // Критично против account takeover: неподтверждённый email НЕ линкуется к чужому аккаунту.
        stubUserSaveAssignsId();
        when(identityRepository.findByProviderAndProviderUserId(any(), any())).thenReturn(Optional.empty());

        AppUser result = service.resolveOrCreate(info("victim@example.com", false));

        // По email НЕ искали (email не verified) → не нашли чужой аккаунт → создали новый.
        verify(userRepository, never()).findByEmailIgnoreCase(any());
        assertThat(result.getId()).isEqualTo(42L);
        assertThat(result.getPasswordHash()).isNull();
        assertThat(result.getPlan()).isEqualTo(UserPlan.FREE);
        assertThat(result.getRole()).isEqualTo(UserRole.USER);
        // P2: неподтверждённый email НЕ попадает в app_users.email (иначе локальный аккаунт нёс бы
        // заявленный чужой адрес), но СОХРАНЯЕТСЯ в oauth_identities.
        assertThat(result.getEmail()).isNull();
        verify(balanceService).createForNewUser(42L, 0); // FREE-квота = 0 (F.1)
        ArgumentCaptor<OAuthIdentity> identity = ArgumentCaptor.forClass(OAuthIdentity.class);
        verify(identityRepository).save(identity.capture());
        assertThat(identity.getValue().getEmail()).isEqualTo("victim@example.com");
        assertThat(identity.getValue().isEmailVerified()).isFalse();
    }

    @Test
    void createsNewAccountWhenVerifiedEmailButNoLocalMatch() {
        stubUserSaveAssignsId();
        when(identityRepository.findByProviderAndProviderUserId(any(), any())).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("new@example.com")).thenReturn(Optional.empty());

        AppUser result = service.resolveOrCreate(info("new@example.com", true));

        assertThat(result.getId()).isEqualTo(42L);
        assertThat(result.getEmail()).isEqualTo("new@example.com");
        assertThat(result.getPasswordHash()).isNull();
        verify(balanceService).createForNewUser(42L, 0);
        verify(identityRepository).save(any(OAuthIdentity.class));
    }

    @Test
    void normalizesEmailToLowerCaseOnNewAccount() {
        stubUserSaveAssignsId();
        when(identityRepository.findByProviderAndProviderUserId(any(), any())).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());

        AppUser result = service.resolveOrCreate(info("MixedCase@Example.COM", true));

        assertThat(result.getEmail()).isEqualTo("mixedcase@example.com");
    }

    @Test
    void handlesNullEmailFromProvider() {
        stubUserSaveAssignsId();
        when(identityRepository.findByProviderAndProviderUserId(any(), any())).thenReturn(Optional.empty());

        AppUser result = service.resolveOrCreate(info(null, false));

        verify(userRepository, never()).findByEmailIgnoreCase(any());
        assertThat(result.getEmail()).isNull();
        verify(identityRepository).save(any(OAuthIdentity.class));
    }

    @Test
    void rejectsBlockedExistingUserOnReturningLogin() {
        OAuthIdentity identity = new OAuthIdentity();
        identity.setUserId(7L);
        when(identityRepository.findByProviderAndProviderUserId(any(), any()))
                .thenReturn(Optional.of(identity));
        AppUser blocked = activeUser(7L, "ivan@example.com");
        blocked.setStatus(UserStatus.BLOCKED);
        when(userRepository.findById(7L)).thenReturn(Optional.of(blocked));

        assertThatThrownBy(() -> service.resolveOrCreate(info("ivan@example.com", true)))
                .isInstanceOf(ComplianceValidationException.class);
    }

    private AppUser activeUser(Long id, String email) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setEmail(email);
        u.setStatus(UserStatus.ACTIVE);
        u.setRole(UserRole.USER);
        u.setPlan(UserPlan.FREE);
        return u;
    }
}
