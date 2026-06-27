package io.okdocs.compliance.rules.common;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Справочник доменов сторонних трекеров с атрибутами {@code provider / country / category}
 * (§ PLAN-jurisdictions Этап 10). Jurisdiction-neutral: используется EU/UK-правилами
 * ({@code EU_THIRD_COUNTRY_TRACKER_RISK}, {@code EU_TRANSFER_NOTICE_MISSING} и т.п.), где важно знать
 * страну провайдера для оценки трансграничной передачи. Country — ISO-2 страны основного
 * расположения провайдера; для GDPR «третья страна» = вне EU/EEA.
 * <p>
 * Это <b>не</b> {@code RuTrackerDomains} (RU-модель «иностранный = риск»): здесь нейтральные
 * атрибуты, а решение «риск/не риск» принимает правило конкретной юрисдикции.
 */
public final class TrackerCatalog {

    /** Категория трекера для текста находки/группировки. */
    public enum Category {
        ANALYTICS,
        ADVERTISING,
        TAG_MANAGER,
        SESSION_REPLAY,
        SOCIAL,
        CUSTOMER_SUPPORT
    }

    /** Атрибуты провайдера трекера. {@code country} — ISO-2 (US, IE, RU, …). */
    public record TrackerInfo(String domain, String provider, String country, Category category) {
    }

    private TrackerCatalog() {
    }

    /** Страны EU/EEA — для GDPR-правила «третья страна» = всё, чего тут нет. */
    private static final java.util.Set<String> EU_EEA = java.util.Set.of(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT",
            "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE", // EU-27
            "IS", "LI", "NO"); // EEA non-EU

    private static final List<TrackerInfo> ENTRIES = List.of(
            new TrackerInfo("google-analytics.com", "Google", "US", Category.ANALYTICS),
            new TrackerInfo("googletagmanager.com", "Google", "US", Category.TAG_MANAGER),
            new TrackerInfo("googleadservices.com", "Google", "US", Category.ADVERTISING),
            new TrackerInfo("doubleclick.net", "Google", "US", Category.ADVERTISING),
            new TrackerInfo("connect.facebook.net", "Meta", "US", Category.ADVERTISING),
            new TrackerInfo("facebook.net", "Meta", "US", Category.ADVERTISING),
            new TrackerInfo("clarity.ms", "Microsoft", "US", Category.ANALYTICS),
            new TrackerInfo("bing.com", "Microsoft", "US", Category.ADVERTISING),
            new TrackerInfo("tiktok.com", "TikTok", "CN", Category.ADVERTISING),
            new TrackerInfo("linkedin.com", "LinkedIn", "US", Category.ADVERTISING),
            new TrackerInfo("licdn.com", "LinkedIn", "US", Category.ADVERTISING),
            new TrackerInfo("ads-twitter.com", "X (Twitter)", "US", Category.ADVERTISING),
            new TrackerInfo("snapchat.com", "Snap", "US", Category.ADVERTISING),
            new TrackerInfo("pinimg.com", "Pinterest", "US", Category.ADVERTISING),
            new TrackerInfo("pinterest.com", "Pinterest", "US", Category.ADVERTISING),
            new TrackerInfo("redditstatic.com", "Reddit", "US", Category.ADVERTISING),
            new TrackerInfo("hotjar.com", "Hotjar", "MT", Category.SESSION_REPLAY),
            new TrackerInfo("fullstory.com", "FullStory", "US", Category.SESSION_REPLAY),
            new TrackerInfo("heapanalytics.com", "Heap", "US", Category.ANALYTICS),
            new TrackerInfo("mixpanel.com", "Mixpanel", "US", Category.ANALYTICS),
            new TrackerInfo("mxpnl.com", "Mixpanel", "US", Category.ANALYTICS),
            new TrackerInfo("amplitude.com", "Amplitude", "US", Category.ANALYTICS),
            new TrackerInfo("segment.com", "Segment", "US", Category.ANALYTICS),
            new TrackerInfo("segment.io", "Segment", "US", Category.ANALYTICS),
            new TrackerInfo("hs-scripts.com", "HubSpot", "US", Category.CUSTOMER_SUPPORT),
            new TrackerInfo("hubspot.com", "HubSpot", "US", Category.CUSTOMER_SUPPORT),
            new TrackerInfo("intercom.io", "Intercom", "US", Category.CUSTOMER_SUPPORT),
            new TrackerInfo("mc.yandex.ru", "Yandex", "RU", Category.ANALYTICS),
            new TrackerInfo("top.mail.ru", "VK", "RU", Category.ANALYTICS));

    private static final Map<String, TrackerInfo> BY_DOMAIN = ENTRIES.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(TrackerInfo::domain, t -> t));

    /** Метаданные трекера по точному совпадению домена или поддомена; empty, если не в каталоге. */
    public static Optional<TrackerInfo> lookup(String domain) {
        if (domain == null) {
            return Optional.empty();
        }
        String d = domain.toLowerCase(Locale.ROOT);
        TrackerInfo exact = BY_DOMAIN.get(d);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (TrackerInfo info : ENTRIES) {
            if (d.equals(info.domain()) || d.endsWith("." + info.domain())) {
                return Optional.of(info);
            }
        }
        return Optional.empty();
    }

    /** Расположен ли провайдер в третьей стране (вне EU/EEA) — риск трансграничной передачи по GDPR. */
    public static boolean isThirdCountry(TrackerInfo info) {
        return info.country() != null && !EU_EEA.contains(info.country());
    }
}
