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
}
