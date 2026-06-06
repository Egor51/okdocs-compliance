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
        boolean hasPrivacyPolicyLink,
        /**
         * Checkbox-согласие с атрибутом {@code checked} по умолчанию. Детектируется даже на
         * STATIC-анализе: атрибут виден в HTML. Вход для {@code ConsentDefaultCheckedRule}.
         */
        boolean hasDefaultCheckedConsent,
        /**
         * Содержит ли форма поле, собирающее персональные данные (email, телефон, имя/ФИО, адрес,
         * паспорт, загрузка файла, пароль и т.п.). Краулер фильтрует технические поля (hidden,
         * submit, csrf, search) и выставляет флаг только при наличии потенциального ПДн-поля.
         * Вход для {@code UnprotectedDataFormsRule} / {@code NoPrivacyPolicyRule}: правила читают
         * готовый флаг, а семантика «какое имя поля = ПДн» (юрисдикционно-зависимая) живёт в
         * краулере, не в jurisdiction-neutral движке правил.
         */
        boolean hasPdField
) {
}
