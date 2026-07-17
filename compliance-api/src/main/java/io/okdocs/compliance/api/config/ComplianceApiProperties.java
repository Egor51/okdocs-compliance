package io.okdocs.compliance.api.config;

import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.UserPlan;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        Security security,
        Oauth oauth,
        Payment payment
) {

    public ComplianceApiProperties {
        if (security == null) {
            security = new Security(null, null);
        }
        if (oauth == null) {
            oauth = new Oauth(null);
        }
        if (payment == null) {
            payment = new Payment(null);
        }
    }

    /**
     * Настройки соц-логина (F.8).
     *
     * @param successRedirectUrl фронтовый BFF-callback (Route Handler), куда success-handler
     *                           редиректит с one-time кодом ({@code ?code=...}). Плейсхолдер
     *                           {@code {locale}} success-handler заменяет на язык интерфейса (из OAuth
     *                           state, F.8); BFF после обмена уводит на {@code /{locale}/dashboard}.
     */
    public record Oauth(String successRedirectUrl) {
        public Oauth {
            if (successRedirectUrl == null || successRedirectUrl.isBlank()) {
                successRedirectUrl = "http://localhost:3000/api/auth/oauth/callback?locale={locale}";
            }
        }
    }

    /**
     * Настройки платежей (F.4/F.16).
     *
     * @param webhookSecret общий секрет для аутентификации webhook'а оплаты — передаётся
     *                      query-параметром {@code token} webhook-URL (YooKassa не умеет кастомные
     *                      header'ы, секрет зашивается в URL при регистрации webhook'а в кабинете).
     *                      Минимальная защита MVP-каркаса от подделки запроса из интернета, пока нет
     *                      штатной проверки подписи провайдера (F.16); факт оплаты дополнительно
     *                      перепроверяется у провайдера ({@code fetchStatus}).
     *                      Если {@code null}/пусто — webhook отвергает ВСЕ запросы (fail-closed),
     *                      чтобы незаданный секрет не открывал бесплатный premium.
     */
    public record Payment(String webhookSecret) {
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
    public record Security(Boolean trustForwardedHeader, List<String> blockedDomains) {
        public Security {
            if (trustForwardedHeader == null) {
                trustForwardedHeader = false;
            }
            blockedDomains = blockedDomains == null ? List.of() : List.copyOf(blockedDomains);
        }
    }

    /**
     * Анти-абьюз лимиты по частоте (§4.2).
     *
     * @param authAttemptsPerIpPerHour лимит попыток аутентификации (login/register/oauth-exchange)
     *                                 с одного IP в час — анти-brute-force поверх стоимости bcrypt.
     *                                 Refresh намеренно не лимитируется: значение токена — 256 бит
     *                                 случайности (перебор невозможен), а кража ловится
     *                                 reuse-detection'ом в {@code AuthService#refresh}; лимит по IP
     *                                 лишь ломал бы офисы за NAT с множеством активных сессий.
     */
    public record RateLimit(Integer guestScansPerIpPerHour, Integer userScansPerHour,
                            Integer authAttemptsPerIpPerHour,
                            Integer remediationRequestsPerIpPerHour) {
        public RateLimit {
            if (guestScansPerIpPerHour == null) {
                guestScansPerIpPerHour = 5;
            }
            if (userScansPerHour == null) {
                userScansPerHour = 20;
            }
            if (authAttemptsPerIpPerHour == null) {
                authAttemptsPerIpPerHour = 30;
            }
            if (remediationRequestsPerIpPerHour == null) {
                remediationRequestsPerIpPerHour = 5;
            }
        }
    }

    /** Параметры скана: лимиты страниц по flow/принципалу и TTL короткоживущих сканов. */
    public record Scan(Integer freeMarketingMaxPages, Integer guestMaxPages, Integer userMaxPages,
                       Integer guestRetentionDays, Integer freeMarketingRetentionDays,
                       Set<ScanJurisdiction> enabledJurisdictions) {
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
            // Юрисдикции с готовым набором правил (§ Этап 13: защита от «пустого идеального отчёта»).
            // GM — устаревшая, не включается. UK включён (Фаза 6: UK GDPR/PECR-правила + overlay).
            if (enabledJurisdictions == null || enabledJurisdictions.isEmpty()) {
                enabledJurisdictions = EnumSet.of(ScanJurisdiction.RU, ScanJurisdiction.EU,
                        ScanJurisdiction.UK, ScanJurisdiction.DE, ScanJurisdiction.FR,
                        ScanJurisdiction.ES);
            } else {
                enabledJurisdictions = EnumSet.copyOf(enabledJurisdictions);
            }
        }
    }

    /** Месячная квота сканов по тарифу. */
    public record Plan(Map<UserPlan, Integer> quota) {
        public Plan {
            Map<UserPlan, Integer> defaults = new EnumMap<>(UserPlan.class);
            defaults.put(UserPlan.FREE, 0); // FREE = 0 premium-квоты (§4c); бесплатен только FREE_MARKETING
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
