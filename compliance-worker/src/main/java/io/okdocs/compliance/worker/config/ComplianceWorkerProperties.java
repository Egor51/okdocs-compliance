package io.okdocs.compliance.worker.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Конфигурация worker'а (§5.7). Префикс {@code compliance}: краулер, лимиты/backpressure, порог
 * reaper'а зависших сканов, путь GeoIP-БД, ретраи outbox и Kafka-топики.
 * <p>
 * {@code @Validated} + JSR-380: невалидный конфиг роняет контекст на старте (fail-fast), а не
 * проявляется багом в рантайме. Лимиты (concurrency, размеры, таймауты) — явная часть контракта
 * продукта, не магические числа в коде.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "compliance")
public class ComplianceWorkerProperties {

    @Valid
    private Crawler crawler = new Crawler();
    @Valid
    private Scan scan = new Scan();
    @Valid
    private GeoIp geoip = new GeoIp();
    @Valid
    private Outbox outbox = new Outbox();
    @Valid
    private Kafka kafka = new Kafka();

    @Data
    public static class Crawler {
        @Min(1)
        private int maxPages = 20;
        @Min(1)
        private int maxDepth = 3;
        @Positive
        private int pageTimeoutMs = 15000;
        @Positive
        private int crawlerTimeoutSeconds = 90;
        /** Задержка между запросами к сайту (вежливый краул). */
        @Min(0)
        private long rateLimitMs = 1000;
        // perDomainConcurrency убран: SiteCrawler по дизайну последовательный (один скан = один поток,
        // один домен), параллелизма per-domain нет — конфиг был бы no-op'ом. Вернуть, если краул
        // станет параллельным.
        /** Жёсткий лимит размера тела ответа (HTML), байт. Защита от OOM на гигантских страницах. */
        @Positive
        private long maxBodyBytes = 5L * 1024 * 1024;
        /** User-Agent краулера (contact policy — должен вести на страницу бота). */
        @NotBlank
        private String userAgent = "OkDocsCompliance/1.0 (+https://okdocs.io/bot)";
        /** Уважать robots.txt (по умолчанию да; отключение — осознанное решение оператора). */
        private boolean respectRobots = true;
        /** Домены, сканирование которых запрещено (anti-abuse, в дополнение к SSRF-проверке). */
        private List<String> blockedDomains = new ArrayList<>();
        /** Если непусто — сканируются ТОЛЬКО эти домены (и поддомены). Пусто = разрешены все публичные. */
        private List<String> allowedDomains = new ArrayList<>();
        @Valid
        private Dynamic dynamic = new Dynamic();
    }

    /**
     * DYNAMIC-краулинг (headless-рендер через CDP) для CABINET_PREMIUM (§5.4). По умолчанию выключен:
     * FREE/гостевые сканы — только STATIC. Включается {@code compliance.crawler.dynamic.enabled=true}
     * + удалённый Chromium через {@code base-url}/{@code auth-token}.
     */
    @Data
    public static class Dynamic {
        private boolean enabled = false;
        /** CDP HTTP/WebSocket endpoint удалённого Chromium (browserless и т.п.). */
        private String baseUrl = "";
        /** Bearer-токен к CDP-эндпоинту. */
        private String authToken = "";
        /** Таймаут navigate на одну страницу (мс). Браузер медленнее Jsoup. */
        @Positive
        private int pageTimeoutMs = 30000;
        /** Параллельных CDP-таргетов (вкладок) внутри одного BrowserContext. */
        @Min(1)
        private int concurrency = 3;
        /** Жёсткий deadline на весь CDP-batch (секунды). */
        @Positive
        private int batchTimeoutSeconds = 180;
    }

    @Data
    public static class Scan {
        /**
         * Порог reaper'а: скан в {@code CRAWLING}/{@code ANALYZING} без апдейта дольше этого срока
         * считается зависшим (§5.3). Строго больше {@code crawler.crawlerTimeoutSeconds}.
         */
        @NotNull
        private Duration staleAfter = Duration.ofMinutes(5);
        /**
         * Задержка повторной доставки (§5.2), когда скан идёт прямо сейчас в другом потоке/инстансе:
         * {@code Acknowledgment.nack(delay)} переставляет offset и повторяет позже, не подтверждая
         * сообщение. Должна быть достаточной, чтобы активный воркер успел продвинуть/завершить скан.
         */
        @NotNull
        private Duration redeliverDelay = Duration.ofSeconds(30);
        /**
         * Жёсткий общий дедлайн на весь скан сверх crawler-таймаута (страховка от зависания не в
         * краулере, а в enrichment/правилах). Должен быть не меньше crawler.crawlerTimeoutSeconds.
         */
        @NotNull
        private Duration totalDeadline = Duration.ofMinutes(3);
    }

    @Data
    public static class GeoIp {
        @NotBlank
        private String dbPath = "classpath:ip-db/dbip-country-lite-2026-06.mmdb";
    }

    @Data
    public static class Outbox {
        /** После исчерпания событие → {@code DEAD}. */
        @Min(1)
        private int maxRetries = 10;
        /** База экспоненциального backoff между ретраями публикации. */
        @NotNull
        private Duration backoffBase = Duration.ofSeconds(10);
        /** Потолок backoff. */
        @NotNull
        private Duration backoffMax = Duration.ofMinutes(10);
    }

    @Data
    public static class Kafka {
        @Valid
        private Topic topic = new Topic();

        @Data
        public static class Topic {
            @NotBlank
            private String scanRequested = "compliance.scan.requested";
            @NotBlank
            private String scanCompleted = "compliance.scan.completed";
            @NotBlank
            private String scanFailed = "compliance.scan.failed";
        }
    }

    /**
     * Кросс-полевой инвариант: порог reaper'а строго больше total-таймаута краулера — иначе reaper
     * добивал бы ещё живой долгий скан. Fail-fast на старте контекста.
     */
    @AssertTrue(message = "compliance.scan.staleAfter must be strictly greater than "
            + "compliance.crawler.crawlerTimeoutSeconds")
    public boolean isReaperThresholdAboveCrawlerTimeout() {
        if (scan == null || scan.getStaleAfter() == null || crawler == null) {
            return true; // отдельные @NotNull/@Valid сообщат конкретнее
        }
        return scan.getStaleAfter().getSeconds() > crawler.getCrawlerTimeoutSeconds();
    }
}
