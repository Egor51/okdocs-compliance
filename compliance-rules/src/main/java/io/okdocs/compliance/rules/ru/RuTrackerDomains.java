package io.okdocs.compliance.rules.ru;

import java.util.Set;

/**
 * Справочники доменов сторонних трекеров под RU-модель (152-ФЗ). {@code FOREIGN} — иностранные
 * получатели (риск трансграничной передачи); {@code ALL} включает российские (Яндекс, VK/Mail.ru),
 * которые тоже должны раскрываться в политике. RU-специфично (для GDPR модель «иностранный = риск»
 * иная), поэтому живёт в пакете {@code ru}, а не в jurisdiction-neutral слое. Перенос из okdocks.
 */
final class RuTrackerDomains {

    private RuTrackerDomains() {
    }

    /** Иностранные сервисы-получатели ПДн (риск трансграничной передачи, ст. 12 152-ФЗ). */
    static final Set<String> FOREIGN = Set.of(
            "google-analytics.com", "googletagmanager.com", "googleadservices.com", "doubleclick.net",
            "connect.facebook.net",
            "bing.com", "clarity.ms",
            "tiktok.com",
            "licdn.com", "linkedin.com",
            "ads-twitter.com",
            "snapchat.com",
            "pinimg.com", "pinterest.com",
            "redditstatic.com",
            "quora.com",
            "hotjar.com", "fullstory.com", "heapanalytics.com",
            "segment.com", "segment.io", "mixpanel.com", "mxpnl.com", "amplitude.com", "rudderlabs.com",
            "hs-scripts.com", "hubspot.com", "intercom.io",
            "stripe.com");

    /** Все трекеры, включая российские (Яндекс, VK/Mail.ru) — должны быть раскрыты в политике. */
    static final Set<String> ALL = Set.of(
            "mc.yandex.ru", "mc.yandex.com", "top.mail.ru", "top-fwz1.mail.ru",
            "google-analytics.com", "googletagmanager.com", "googleadservices.com", "doubleclick.net",
            "connect.facebook.net",
            "bing.com", "clarity.ms",
            "tiktok.com",
            "licdn.com", "linkedin.com",
            "ads-twitter.com",
            "snapchat.com",
            "pinimg.com", "pinterest.com",
            "redditstatic.com",
            "quora.com",
            "hotjar.com", "fullstory.com", "heapanalytics.com",
            "segment.com", "segment.io", "mixpanel.com", "mxpnl.com", "amplitude.com", "rudderlabs.com",
            "hs-scripts.com", "hubspot.com", "intercom.io");
}
