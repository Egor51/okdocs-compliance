package io.okdocs.compliance.mail.template;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HandlebarsMailTemplateRendererTest {

    private final HandlebarsMailTemplateRenderer renderer = new HandlebarsMailTemplateRenderer();

    @Test
    void rendersLocalizedTemplateAndEscapesModel() {
        String html = renderer.render("welcome", "en-US", Map.of(
                "name", "<script>alert(1)</script>", "email", "a@example.com"));
        assertThat(html).contains("Welcome to OKDOCS");
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void unsupportedLocaleFallsBackToRussian() {
        assertThat(renderer.render("welcome", "de", Map.of("name", "Ivan", "email", "a@b.c")))
                .contains("Добро пожаловать");
    }

    @Test
    void rendersRemediationNotificationAndEscapesLeadData() {
        String html = renderer.render("remediation_request", "ru", Map.of(
                "requestId", "request-id",
                "siteUrl", "https://example.com/?q=<script>",
                "name", "<b>Иван</b>",
                "email", "ivan@example.com",
                "phone", "+7 999 000-00-00",
                "submittedAt", "2026-07-16T08:00:00Z"));

        assertThat(html).contains("Новая заявка", "ivan@example.com", "request-id");
        assertThat(html).doesNotContain("<script>", "<b>Иван</b>");
    }

    @Test
    void rendersMonitoringAlertAndEscapesDomain() {
        String html = renderer.render("monitoring_alert", "en", Map.of(
                "siteDomain", "<script>example.com</script>",
                "previousScore", 80,
                "currentScore", 70,
                "newFindings", 2,
                "resolvedFindings", 1,
                "reportUrl", "https://okdocs.io/en/dashboard/reports/id"));

        assertThat(html).contains("risks have changed", "80", "70");
        assertThat(html).doesNotContain("<script>");
    }
}
