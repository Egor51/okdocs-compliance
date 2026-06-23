package io.okdocs.compliance.api.security.oauth;

import io.okdocs.compliance.contracts.auth.OAuthUserInfo;
import io.okdocs.compliance.contracts.enums.OAuthProvider;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthUserInfoFactoryTest {

    @Test
    void googleMapsVerifiedEmail() {
        OAuthUserInfo info = OAuthUserInfoFactory.from("google", Map.of(
                "sub", "g-1", "email", "a@gmail.com", "email_verified", true, "name", "Ann"));

        assertThat(info.provider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(info.providerUserId()).isEqualTo("g-1");
        assertThat(info.email()).isEqualTo("a@gmail.com");
        assertThat(info.emailVerified()).isTrue();
        assertThat(info.name()).isEqualTo("Ann");
    }

    @Test
    void googleRespectsUnverifiedEmailFlag() {
        OAuthUserInfo info = OAuthUserInfoFactory.from("google", Map.of(
                "sub", "g-1", "email", "a@gmail.com", "email_verified", false));
        assertThat(info.emailVerified()).isFalse();
    }

    @Test
    void githubNeverMarksEmailVerified_safeDefault() {
        // Базовый профиль GitHub не несёт флага подтверждённости → false (защита от takeover, F.9).
        OAuthUserInfo info = OAuthUserInfoFactory.from("github", Map.of(
                "id", 42, "email", "dev@example.com", "login", "octocat"));

        assertThat(info.provider()).isEqualTo(OAuthProvider.GITHUB);
        assertThat(info.providerUserId()).isEqualTo("42");
        assertThat(info.emailVerified()).isFalse();
        assertThat(info.name()).isEqualTo("octocat"); // fallback на login при отсутствии name
    }

    @Test
    void yandexMarksEmailVerifiedWhenPresent() {
        OAuthUserInfo info = OAuthUserInfoFactory.from("yandex", Map.of(
                "id", "y-1", "default_email", "user@yandex.ru", "real_name", "Иван Иванов"));

        assertThat(info.provider()).isEqualTo(OAuthProvider.YANDEX);
        assertThat(info.email()).isEqualTo("user@yandex.ru");
        assertThat(info.emailVerified()).isTrue();
        assertThat(info.name()).isEqualTo("Иван Иванов");
    }

    @Test
    void yandexFallsBackToEmailsListAndDisplayName() {
        OAuthUserInfo info = OAuthUserInfoFactory.from("yandex", Map.of(
                "id", "y-2", "emails", List.of("first@yandex.ru", "second@yandex.ru"),
                "display_name", "ivan"));
        assertThat(info.email()).isEqualTo("first@yandex.ru");
        assertThat(info.name()).isEqualTo("ivan");
    }

    @Test
    void yandexWithoutEmailIsNotVerified() {
        OAuthUserInfo info = OAuthUserInfoFactory.from("yandex", Map.of("id", "y-3"));
        assertThat(info.email()).isNull();
        assertThat(info.emailVerified()).isFalse();
    }

    @Test
    void vkNeverMarksEmailVerified_joinsName() {
        OAuthUserInfo info = OAuthUserInfoFactory.from("vk", Map.of(
                "id", 777, "first_name", "Пётр", "last_name", "Петров"));

        assertThat(info.provider()).isEqualTo(OAuthProvider.VK);
        assertThat(info.providerUserId()).isEqualTo("777");
        assertThat(info.emailVerified()).isFalse();
        assertThat(info.name()).isEqualTo("Пётр Петров");
    }

    @Test
    void unknownProviderRejected() {
        assertThatThrownBy(() -> OAuthUserInfoFactory.from("facebook", Map.of("id", "1")))
                .isInstanceOf(ComplianceValidationException.class);
    }

    @Test
    void registrationIdIsCaseInsensitive() {
        OAuthUserInfo info = OAuthUserInfoFactory.from("GOOGLE", Map.of("sub", "g", "email_verified", true));
        assertThat(info.provider()).isEqualTo(OAuthProvider.GOOGLE);
    }
}
