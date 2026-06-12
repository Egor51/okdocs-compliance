package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.FormInfo;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.RenderMode;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ForeignAuthProviderRuleTest {

    private final ForeignAuthProviderRule rule = new ForeignAuthProviderRule();

    // Форма с password-полем = login-контекст (TestFixtures без password-формы).
    private static FormInfo loginForm() {
        return new FormInfo("/login", "POST", List.of("email", "password"),
                true, false, false, false, false, false, true);
    }

    @Test
    void definitionMatchesPlan() {
        assertThat(rule.definition().code()).isEqualTo("POSSIBLE_FOREIGN_AUTH_PROVIDER");
        assertThat(rule.definition().severity()).isEqualTo(FindingSeverity.HIGH);
        assertThat(rule.definition().category()).isEqualTo(FindingCategory.FORMS);
    }

    @Test
    void flagsGoogleSdkWithLoginFormStrong() {
        // SDK-домен + кнопочный маркер + login-форма → два класса сигналов, UNVERIFIED 0.85.
        PageAnalysisResult page = TestFixtures.page(
                "https://site.ru/login", "Вход в личный кабинет", false,
                List.of(loginForm()), List.of(), List.of("accounts.google.com"),
                "<div class=\"g_id_signin\">Войти через Google</div>");

        List<RuleFact> facts = rule.evaluate(TestFixtures.ctx(page));

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
            assertThat(f.confidence()).isEqualTo(0.85);
            assertThat(f.matchedSignals()).contains("accounts.google.com");
        });
    }

    @Test
    void lowerConfidenceWhenOnlyButtonMarker() {
        // Только текстовый маркер «Войти через Apple», без SDK-домена → один класс сигналов, 0.65.
        PageAnalysisResult page = TestFixtures.page(
                "https://site.ru/login", "Авторизация", false,
                List.of(loginForm()), List.of(), List.of(),
                "<button class=\"apple-signin\">Continue with Apple</button>");

        assertThat(rule.evaluate(TestFixtures.ctx(page))).singleElement().satisfies(f -> {
            assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
            assertThat(f.confidence()).isEqualTo(0.65);
        });
    }

    @Test
    void confirmedOnDynamicRender() {
        PageAnalysisResult dynamic = new PageAnalysisResult(
                "https://site.ru/login", "Вход", "войти через google", "<div class=\"g_id_signin\"/>",
                List.of("accounts.google.com"), List.of(), List.of(),
                false, List.of(loginForm()), RenderMode.DYNAMIC);

        assertThat(rule.evaluate(TestFixtures.ctx(dynamic))).singleElement().satisfies(f -> {
            assertThat(f.verificationStatus()).isEqualTo(VerificationStatus.CONFIRMED);
            assertThat(f.confidence()).isEqualTo(1.0);
        });
    }

    @Test
    void ignoresRussianProviders() {
        // VK ID / Yandex ID — не иностранные, трансграничного состава не образуют.
        PageAnalysisResult page = TestFixtures.page(
                "https://site.ru/login", "Вход", false,
                List.of(loginForm()), List.of(), List.of("id.vk.com", "oauth.yandex.ru"),
                "<div>Войти через VK</div>");

        assertThat(rule.evaluate(TestFixtures.ctx(page))).isEmpty();
    }

    @Test
    void ignoresShareWidgetWithoutLoginContext() {
        // connect.facebook.net без login-контекста и без auth-маркера — share-виджет, не вход.
        // Его покрывают ThirdPartyTrackersRule/CrossBorderTransferRule.
        PageAnalysisResult page = TestFixtures.page(
                "https://site.ru/blog", "Статья блога", false,
                List.of(), List.of(), List.of("connect.facebook.net"),
                "<div class=\"fb-share-button\">Поделиться</div>");

        assertThat(rule.evaluate(TestFixtures.ctx(page))).isEmpty();
    }

    @Test
    void perPageNoLeak() {
        // Провайдер на login-странице помечается; обычная страница с тем же доменом, но без
        // login-контекста — нет.
        PageAnalysisResult login = TestFixtures.page(
                "https://site.ru/login", "Вход", false,
                List.of(loginForm()), List.of(), List.of("accounts.google.com"),
                "<div class=\"g_id_signin\"/>");
        PageAnalysisResult other = TestFixtures.page(
                "https://site.ru/about", "О нас", false,
                List.of(), List.of(), List.of("accounts.google.com"),
                "<html></html>");

        List<RuleFact> facts = rule.evaluate(TestFixtures.ctx(login, other));

        assertThat(facts).singleElement().satisfies(f ->
                assertThat(f.sourceUrl()).isEqualTo("https://site.ru/login"));
    }
}
