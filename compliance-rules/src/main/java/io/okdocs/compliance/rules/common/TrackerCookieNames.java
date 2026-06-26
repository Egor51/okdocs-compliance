package io.okdocs.compliance.rules.common;

import java.util.List;
import java.util.Locale;

/**
 * Префиксы/подстроки имён cookie и ключей Web Storage, характерных для аналитических и маркетинговых
 * трекеров (RU-модель: Яндекс.Метрика, Google Analytics, Meta Pixel, mail.ru/VK и зарубежные SaaS).
 * Используется cookie-правилами Этапа 4 для классификации «трекинговая ли cookie/ключ» — по аналогии
 * с {@link RuTrackerDomains}, но по именам, а не доменам. Список эвристический и неполный.
 */
public final class TrackerCookieNames {

    /** Подстроки имён трекинговых cookie/storage-ключей (lowercase). */
    static final List<String> MARKERS = List.of(
            "_ga", "_gid", "_gat", "_gcl", "__utm",          // Google Analytics / Ads
            "_ym_", "yandexuid", "yabs-sid", "ymex",         // Яндекс.Метрика
            "_fbp", "_fbc", "fr",                            // Meta / Facebook Pixel
            "mc_", "tmr_", "_mailru",                        // mail.ru / VK
            "amplitude", "amp_", "mp_",                      // Amplitude / Mixpanel
            "_hjsession", "hjid", "hjviewportid",            // Hotjar
            "ajs_", "_cs_",                                  // Segment / прочие SaaS
            "intercom-", "__hstc", "hubspotutk",             // Intercom / HubSpot
            "_clck", "_clsk",                                // Microsoft Clarity
            "_ttp", "ttclid");                               // TikTok

    private TrackerCookieNames() {
    }

    /** Похоже ли имя cookie/ключа на трекинговый маркер (case-insensitive подстрока). */
    public static boolean isTracker(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        for (String marker : MARKERS) {
            if (n.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
