package io.okdocs.compliance.mail.template;

import java.util.Map;

public interface MailTemplateRenderer {
    String render(String templateName, String locale, Map<String, Object> model);
}
