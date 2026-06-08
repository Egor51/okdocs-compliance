package io.okdocs.compliance.api.config;

import io.okdocs.compliance.contracts.enums.UserPlan;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Конфигурация compliance-api (prefix {@code compliance}, §4.4).
 * Outbox-настройки живут отдельно в {@code compliance.outbox} (модуль messaging).
 */
@ConfigurationProperties(prefix = "compliance")
public record ComplianceApiProperties(
        KafkaTopics kafka,
        RateLimit rateLimit,
        Scan scan,
        Plan plan,
        PaywallCta paywallCta,
        Auth auth,
        Security security
) {

    public ComplianceApiProperties {
        if (security == null) {
            security = new Security(null);
        }
    }

    /** Топики Kafka назначения для outbox-публикации. */
    public record KafkaTopics(Topic topic) {
        public record Topic(String scanRequested, String scanCompleted, String scanFailed) {
        }
    }

    /**
     * Сетевые настройки безопасности.
     *
     * @param trustForwardedHeader доверять ли {@code X-Forwarded-For} для определения IP клиента.
     *                             По умолчанию {@code false}: иначе анонимный клиент подделкой
     *                             заголовка обходит guest-rate-limit по IP. Включать ТОЛЬКО когда
     *                             приложение недоступно напрямую и заголовок переписывает
     *                             доверенный ingress/proxy.
     */
    public record Security(Boolean trustForwardedHeader) {
        public Security {
            if (trustForwardedHeader == null) {
                trustForwardedHeader = false;
            }
        }
    }

    /** Анти-абьюз лимиты по частоте (§4.2). */
    public record RateLimit(Integer guestScansPerIpPerHour, Integer userScansPerHour) {
        public RateLimit {
            if (guestScansPerIpPerHour == null) {
                guestScansPerIpPerHour = 5;
            }
            if (userScansPerHour == null) {
                userScansPerHour = 20;
            }
        }
    }

    /** Параметры скана: лимиты страниц по flow/принципалу и TTL короткоживущих сканов. */
    public record Scan(Integer freeMarketingMaxPages, Integer guestMaxPages, Integer userMaxPages,
                       Integer guestRetentionDays, Integer freeMarketingRetentionDays) {
        public Scan {
            if (freeMarketingMaxPages == null) {
                freeMarketingMaxPages = 1; // FREE_MARKETING — лид-магнит: главная страница
            }
            if (guestMaxPages == null) {
                guestMaxPages = 5;
            }
            if (userMaxPages == null) {
                userMaxPages = 30;
            }
            if (guestRetentionDays == null) {
                guestRetentionDays = 7;
            }
            if (freeMarketingRetentionDays == null) {
                freeMarketingRetentionDays = 7; // короткоживущий лид-магнит
            }
        }
    }

    /** Месячная квота сканов по тарифу. */
    public record Plan(Map<UserPlan, Integer> quota) {
        public Plan {
            Map<UserPlan, Integer> defaults = new EnumMap<>(UserPlan.class);
            defaults.put(UserPlan.FREE, 1);
            defaults.put(UserPlan.PRO, 30);
            defaults.put(UserPlan.BUSINESS, 200);
            if (quota != null) {
                defaults.putAll(quota);
            }
            quota = Map.copyOf(defaults);
        }

        public int quotaFor(UserPlan plan) {
            return quota.getOrDefault(plan, 0);
        }
    }

    /** Призыв к покупке PREMIUM (FREE-отчёт). */
    public record PaywallCta(String title, String text, String actionUrl) {
    }

    /** JWT-настройки. */
    public record Auth(String jwtSecret, Duration accessTokenTtl, Duration refreshTokenTtl, Duration guestTokenTtl) {
        public Auth {
            if (accessTokenTtl == null) {
                accessTokenTtl = Duration.ofMinutes(30);
            }
            if (refreshTokenTtl == null) {
                refreshTokenTtl = Duration.ofDays(30);
            }
            if (guestTokenTtl == null) {
                guestTokenTtl = Duration.ofDays(7);
            }
        }
    }
}
