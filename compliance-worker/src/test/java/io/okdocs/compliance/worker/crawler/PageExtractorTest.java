package io.okdocs.compliance.worker.crawler;

import io.okdocs.compliance.contracts.crawler.FormInfo;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.enums.RenderMode;
import io.okdocs.compliance.contracts.enums.FormPurpose;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Extraction-слой написан заново под контрактный {@link PageAnalysisResult}/{@link FormInfo} —
 * именно эти флаги читают RU-правила. Тест фиксирует семантику флагов (PD-поля, согласие,
 * default-checked, ссылка на политику, cookie, внешние домены, RenderMode).
 */
class PageExtractorTest {

    private static PageAnalysisResult extract(String html, RenderMode mode) {
        Document doc = Jsoup.parse(html, "https://site.ru/");
        return PageExtractor.extract("https://site.ru/", doc, "site.ru", mode);
    }

    private static PageAnalysisResult extract(String html) {
        return extract(html, RenderMode.STATIC);
    }

    @Test
    void detectsPdFieldByInputName() {
        var page = extract("<form><input type='text' name='email'><input type='submit'></form>");
        FormInfo form = page.forms().get(0);
        assertThat(form.hasPdField()).isTrue();
        assertThat(form.hasPasswordField()).isFalse();
    }

    @Test
    void ignoresTechnicalFieldsForPd() {
        // hidden/submit/search не считаются ПДн-полями
        var page = extract("<form>"
                + "<input type='hidden' name='csrf'>"
                + "<input type='submit' name='go'>"
                + "<input type='search' name='q'>"
                + "</form>");
        assertThat(page.forms().get(0).hasPdField()).isFalse();
    }

    @Test
    void detectsPasswordAndFileUpload() {
        var page = extract("<form>"
                + "<input type='password' name='pwd'>"
                + "<input type='file' name='doc'>"
                + "</form>");
        FormInfo form = page.forms().get(0);
        assertThat(form.hasPasswordField()).isTrue();
        assertThat(form.hasFileUpload()).isTrue();
    }

    @Test
    void classifiesCurrentPasswordFormAsLogin() {
        Document doc = Jsoup.parse("<form><input type='email' autocomplete='email'>"
                + "<input type='password' autocomplete='current-password'></form>",
                "https://site.ru/login");
        var page = PageExtractor.extract("https://site.ru/login", doc, "site.ru");

        assertThat(page.forms().get(0).purpose()).isEqualTo(FormPurpose.AUTH_LOGIN);
    }

    @Test
    void classifiesEmailFirstFormOnLoginPageAsLogin() {
        Document doc = Jsoup.parse("<form><input type='email' autocomplete='email'></form>",
                "https://site.ru/login");
        var page = PageExtractor.extract("https://site.ru/login", doc, "site.ru");

        assertThat(page.forms().get(0).purpose()).isEqualTo(FormPurpose.AUTH_LOGIN);
    }

    @Test
    void classifiesNewPasswordFormAsRegistration() {
        Document doc = Jsoup.parse("<form><input type='email'><input type='password' "
                + "autocomplete='new-password'></form>", "https://site.ru/register");
        var page = PageExtractor.extract("https://site.ru/register", doc, "site.ru");

        assertThat(page.forms().get(0).purpose()).isEqualTo(FormPurpose.AUTH_REGISTER);
    }

    @Test
    void classifiesResetPasswordBeforeNewPasswordRegistrationHeuristic() {
        Document doc = Jsoup.parse("<form><input type='password' autocomplete='new-password'></form>",
                "https://site.ru/reset-password");
        var page = PageExtractor.extract("https://site.ru/reset-password", doc, "site.ru");

        assertThat(page.forms().get(0).purpose()).isEqualTo(FormPurpose.PASSWORD_RECOVERY);
    }

    @Test
    void detectsConsentTextAndCheckbox() {
        var page = extract("<form>"
                + "<input type='text' name='name'>"
                + "<input type='checkbox' name='agree'> Я согласен на обработку персональных данных"
                + "</form>");
        FormInfo form = page.forms().get(0);
        assertThat(form.hasCheckbox()).isTrue();
        assertThat(form.hasConsentText()).isTrue();
        assertThat(form.hasDefaultCheckedConsent()).isFalse();
    }

    @Test
    void detectsDefaultCheckedConsent() {
        // checkbox согласия с атрибутом checked — вход для ConsentDefaultCheckedRule
        var page = extract("<form>"
                + "<input type='checkbox' name='consent' checked> Согласие на обработку"
                + "</form>");
        assertThat(page.forms().get(0).hasDefaultCheckedConsent()).isTrue();
    }

    @Test
    void detectsPrivacyPolicyLinkInForm() {
        var page = extract("<form>"
                + "<input type='text' name='phone'>"
                + "<a href='/privacy'>Политика конфиденциальности</a>"
                + "</form>");
        assertThat(page.forms().get(0).hasPrivacyPolicyLink()).isTrue();
    }

    @Test
    void detectsCookieBannerFlag() {
        var withBanner = extract("<div class='cookie-consent'>Сайт использует cookie</div>");
        assertThat(withBanner.cookiePresent()).isTrue();

        var without = extract("<div>обычный контент</div>");
        assertThat(without.cookiePresent()).isFalse();
    }

    @Test
    void collectsExternalScriptAndStyleDomains() {
        var page = extract("<html><head>"
                + "<script src='https://mc.yandex.ru/metrika/tag.js'></script>"
                + "<link rel='stylesheet' href='https://fonts.googleapis.com/css'>"
                + "<script src='/local.js'></script>"
                + "</head></html>");
        assertThat(page.externalScriptDomains()).contains("mc.yandex.ru");
        assertThat(page.externalScriptDomains()).doesNotContain("site.ru"); // свой домен не внешний
        assertThat(page.externalStyleDomains()).contains("fonts.googleapis.com");
    }

    @Test
    void detectsExternalDomainFromInlineScript() {
        var page = extract("<script>(function(){var s='https://www.google-analytics.com/analytics.js';})()</script>");
        assertThat(page.externalScriptDomains()).contains("www.google-analytics.com");
    }

    @Test
    void collectsOnlyInternalLinks() {
        var page = extract("<a href='https://site.ru/about'>about</a>"
                + "<a href='https://external.com/x'>ext</a>"
                + "<a href='https://sub.site.ru/y'>sub</a>");
        assertThat(page.internalLinks()).contains("https://site.ru/about", "https://sub.site.ru/y");
        assertThat(page.internalLinks()).noneMatch(l -> l.contains("external.com"));
    }

    @Test
    void preservesRenderMode() {
        assertThat(extract("<p>x</p>", RenderMode.STATIC).renderMode()).isEqualTo(RenderMode.STATIC);
        assertThat(extract("<p>x</p>", RenderMode.DYNAMIC).renderMode()).isEqualTo(RenderMode.DYNAMIC);
    }

    @Test
    void staticConvenienceMethodDefaultsToStatic() {
        Document doc = Jsoup.parse("<p>x</p>", "https://site.ru/");
        assertThat(PageExtractor.extract("https://site.ru/", doc, "site.ru").renderMode())
                .isEqualTo(RenderMode.STATIC);
    }
}
