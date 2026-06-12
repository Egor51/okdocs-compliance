package io.okdocs.compliance.rules.ru;

import java.util.Set;

/**
 * Справочник иностранных провайдеров федеративного входа (Sign in with Google / Apple / Facebook
 * и т.п.) под RU-модель (152-ФЗ). Вход через такой сервис = передача идентификаторов и профильных
 * ПДн стороннему ИНОСТРАННОМУ оператору → риск трансграничной передачи (ст. 12) + обработка третьим
 * лицом (ст. 6, 18.1). Используется {@link ForeignAuthProviderRule}.
 * <p>
 * Российские провайдеры (VK ID, Yandex ID, Сбер ID, Госуслуги) сюда НЕ входят: трансграничного
 * состава они не образуют (как {@code mc.yandex.ru} исключён из {@link RuTrackerDomains#FOREIGN}).
 * «Иностранность» RU-специфична, поэтому справочник живёт в пакете {@code ru}, а не в
 * jurisdiction-neutral слое.
 */
final class RuForeignAuthProviders {

    private RuForeignAuthProviders() {
    }

    /** Домены SDK/endpoint'ов иностранных OAuth/OIDC-провайдеров. */
    static final Set<String> SDK_DOMAINS = Set.of(
            "accounts.google.com", "apis.google.com", "gsi.gstatic.com",
            "appleid.apple.com", "appleid.cdn-apple.com",
            "connect.facebook.net", "graph.facebook.com",
            "login.microsoftonline.com", "login.live.com",
            "github.com", "api.github.com",
            "discord.com",
            "api.twitter.com", "x.com",
            "auth0.com", "okta.com");
}
