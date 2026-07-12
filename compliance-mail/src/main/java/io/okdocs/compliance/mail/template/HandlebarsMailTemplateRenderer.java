package io.okdocs.compliance.mail.template;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.io.ClassPathTemplateLoader;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HandlebarsMailTemplateRenderer implements MailTemplateRenderer {

    private final Handlebars handlebars = new Handlebars(
            new ClassPathTemplateLoader("/templates/mail", ".hbs"));
    private final Map<String, Template> cache = new ConcurrentHashMap<>();

    @Override
    public String render(String templateName, String locale, Map<String, Object> model) {
        String normalized = normalizeLocale(locale);
        String localized = normalized + "/" + templateName;
        try {
            Template template = cache.computeIfAbsent(localized, key -> compile(key, "ru/" + templateName));
            return template.apply(model);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to render mail template " + localized, e);
        }
    }

    public static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) return "ru";
        String value = locale.trim().toLowerCase(java.util.Locale.ROOT);
        int separator = value.indexOf('-');
        if (separator > 0) value = value.substring(0, separator);
        return "en".equals(value) ? "en" : "ru";
    }

    private Template compile(String localized, String fallback) {
        try {
            return handlebars.compile(localized);
        } catch (IOException missingLocalized) {
            try {
                return handlebars.compile(fallback);
            } catch (IOException missingFallback) {
                throw new IllegalStateException("Mail template not found: " + localized, missingFallback);
            }
        }
    }
}
