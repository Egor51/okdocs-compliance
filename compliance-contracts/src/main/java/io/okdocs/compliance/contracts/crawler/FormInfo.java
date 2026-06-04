package io.okdocs.compliance.contracts.crawler;

import java.util.List;

/** Распознанная форма на странице. Вход для правил FORMS/CONSENT. */
public record FormInfo(
        String action,
        String method,
        List<String> inputNames,
        boolean hasPasswordField,
        boolean hasFileUpload,
        boolean hasCheckbox,
        boolean hasConsentText,
        boolean hasPrivacyPolicyLink
) {
}
