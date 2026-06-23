package io.okdocs.compliance.contracts.enums;

/**
 * Провайдер соц-логина. Набор доступных провайдеров на фронте определяется по locale
 * (ru → Яндекс/VK, en → Google/GitHub), но enum общий — бэкенд принимает любой.
 */
public enum OAuthProvider {
    YANDEX,
    VK,
    GOOGLE,
    GITHUB
}
