package io.okdocs.compliance.worker.config;

import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
    private Security security = new Security();
    @Valid
    private Scan scan = new Scan();
    @Valid
    private Score score = new Score();
    @Valid
    private GeoIp geoip = new GeoIp();
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
        /**
         * Таймаут TCP-connect (мс), отдельный от {@link #pageTimeoutMs} (read). Короче read-таймаута:
         * мёртвый/недостижимый seed (NoRouteToHost/ConnectionRefused) должен отваливаться быстро, не
         * съедая 15с × число resolved-адресов — иначе перебор приоритетных хинтов на SPA без этих
         * путей растягивается на минуту. Живые-но-медленные страницы read-таймаут не теряют.
         */
        @Positive
        private int connectTimeoutMs = 4000;
        @Positive
        private int crawlerTimeoutSeconds = 90;
        /**
         * Задержка между запросами к сайту НА ОДИН ПОТОК (вежливый краул). При concurrency потоков
         * фактический rps на домен ≈ concurrency / (rateLimitMs/1000). 500мс × 5 потоков ≈ 10 rps.
         */
        @Min(0)
        private long rateLimitMs = 500;
        /**
         * Число параллельных fetch-потоков static-краула (один домен, N страниц одновременно). Сам
         * пул служит throttle'ом нагрузки на сайт (см. rateLimitMs). 1 = старое последовательное
         * поведение.
         */
        @Min(1)
        private int concurrency = 5;
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
        /** Если непусто — сканируются ТОЛЬКО эти домены (и поддомены). Пусто = разрешены все публичные. */
        private List<String> allowedDomains = new ArrayList<>();
        @Valid
        private Dynamic dynamic = new Dynamic();
    }

    @Data
    public static class Security {
        /** Единый с API anti-abuse список из application-domain-policy.yml. */
        private List<String> blockedDomains = new ArrayList<>();
    }

    /**
     * DYNAMIC-краулинг (headless-рендер через CDP) для CABINET_PREMIUM (§5.4). По умолчанию выключен:
     * FREE/гостевые сканы — только STATIC. Включается {@code compliance.crawler.dynamic.enabled=true}
     * + удалённый Chromium через {@code base-url}/{@code auth-token}.
     * <p>
     * {@code premiumEnabled} — явное «этот деплой обслуживает платный premium-поток». Premium-сканы
     * требуют CDP ({@code dynamic_required}); если CDP не сконфигурирован, каждый такой скан падает в
     * FAILED+refund. Чтобы это не было тихой ловушкой дефолта (free-сканы идут, а весь платный поток
     * мёртв и обнаруживается только на реальном платеже), {@link #premiumRequiresCdp()} роняет
     * контекст воркера на старте, если premium включён, но CDP недоступен. Локально/на стейдже, где
     * premium осознанно не нужен, ставят {@code premium-enabled=false} — старт проходит, а отказ
     * premium-сканов становится явным выбором, а не дефолтом.
     */
    @Data
    public static class Dynamic {
        private boolean enabled = false;
        /** Этот деплой обслуживает платный premium-поток (требует доступного CDP). */
        private boolean premiumEnabled = true;
        /**
         * HTTP CDP endpoint удалённого Chromium (browserless и т.п.): {@code http://browserless:3000}.
         * Это именно HTTP-эндпоинт (Chrome отдаёт {@code /json/version}, {@code /json/list}), а не
         * WebSocket: {@code ws://}-URL ведёт прямо в один debug-сокет и ломает discovery таргетов.
         * WS-адреса краулер выводит сам из {@code /json/version} ({@code resolveWsUrl}).
         */
        private String baseUrl = "";
        /** Bearer-токен к CDP-эндпоинту. */
        private String authToken = "";
        /** Таймаут navigate на одну страницу (мс). Браузер медленнее Jsoup. */
        @Positive
        private int pageTimeoutMs = 30000;
        /** Параллельных CDP-таргетов (вкладок) внутри одного BrowserContext. */
        @Min(1)
        private int concurrency = 3;
        /**
         * Сколько страниц premium-скана рендерить через CDP поверх static-карта сайта. Java-дефолт
         * синхронизирован с application-compliance-core.yml (10): dynamic-проход — главный потребитель
         * времени отчёта, приоритетные страницы рендерятся первыми, хвост урезается.
         */
        @Min(1)
        private int maxPages = 10;
        /** Жёсткий deadline на весь CDP-batch (секунды). */
        @Positive
        private int batchTimeoutSeconds = 180;
        /** Как часто перепроверять живость CDP-эндпоинта ({@code /json/version}) между сканами. */
        @NotNull
        private Duration availabilityRecheckInterval = Duration.ofSeconds(10);
        @Valid
        private NetworkIdle networkIdle = new NetworkIdle();
        @Valid
        private PreConsentTracking preConsentTracking = new PreConsentTracking();

        /**
         * Эвристика «динамика догрузилась»: ждём {@code quietMs} мс сетевой тишины подряд, но не
         * дольше {@code timeoutMs} (защита от long-polling/websocket, которые тишины не дают).
         */
        @Data
        public static class NetworkIdle {
            @Positive
            private int quietMs = 800;
            @Positive
            private int timeoutMs = 2000;
        }

        /**
         * Наблюдение «трекер до согласия» (§3.2): инжект MutationObserver фиксирует момент появления
         * cookie-баннера, таймлайн запросов — что грузилось раньше. При {@code enabled=false} краулер
         * не инжектит наблюдатель и отдаёт пустой {@code preConsentTrackerHosts} → правило остаётся на
         * вероятностном UNVERIFIED. Откат-рубильник на случай, если инжект ломает экзотические страницы.
         */
        @Data
        public static class PreConsentTracking {
            private boolean enabled = true;
        }

        private ConsentScenarios consentScenarios = new ConsentScenarios();

        /**
         * Прогон consent-сценариев (Фаза 4): после снимка «до согласия» краулер кликает Reject, затем
         * Accept, фиксируя cookies/трекеры после каждого действия (вход для EU/UK consent-правил).
         * Best-effort и дороже по времени (доп. сетевые ожидания), поэтому по умолчанию {@code false} —
         * включается на сканах EU/UK-юрисдикций. {@code waitAfterClickMs} — пауза на догрузку после
         * клика перед снимком.
         */
        @Data
        public static class ConsentScenarios {
            private boolean enabled = false;
            @Positive
            private int waitAfterClickMs = 1500;
        }

        /**
         * Premium-поток обещан, но CDP не сконфигурирован → невалидно: контекст не стартует. Premium
         * требует {@code enabled=true} + непустой {@code base-url}. Сообщение — операторам, как чинить.
         */
        @AssertTrue(message = "compliance.crawler.dynamic.premium-enabled=true требует enabled=true и "
                + "непустой base-url (CDP-эндпоинт). Иначе все CABINET_PREMIUM-сканы будут FAILED+refund. "
                + "Для деплоя без платного потока выставьте premium-enabled=false.")
        public boolean isPremiumRequiresCdp() {
            return !premiumEnabled || (enabled && baseUrl != null && !baseUrl.isBlank());
        }

        /**
         * {@code base-url} (если задан) обязан быть HTTP CDP-эндпоинтом: {@code http://} или
         * {@code https://}. {@code ws://}/{@code wss://} — частая ошибка конфигурации: краулер ходит
         * на {@code /json/version} по HTTP и сам выводит WS-адрес таргета; ws-base-url ломает discovery.
         */
        @AssertTrue(message = "compliance.crawler.dynamic.base-url должен быть HTTP CDP-эндпоинтом "
                + "(http:// или https://), напр. http://browserless:3000. ws://...|wss://... недопустимо: "
                + "WS-адрес краулер выводит из /json/version сам.")
        public boolean isBaseUrlHttpScheme() {
            if (baseUrl == null || baseUrl.isBlank()) {
                return true; // пустой base-url ловит isPremiumRequiresCdp, когда premium включён
            }
            String lower = baseUrl.trim().toLowerCase(java.util.Locale.ROOT);
            return lower.startsWith("http://") || lower.startsWith("https://");
        }
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

    private Backfill backfill = new Backfill();

    /**
     * Разовый backfill отчётных снапшотов (этап 3.5 переноса сборки отчёта в worker): для старых
     * terminal-сканов ({@code COMPLETED}/{@code PARTIAL}) без строки в {@code compliance_scan_reports}
     * строит и сохраняет snapshot, чтобы API мог отдавать их через snapshot-путь без доменной сборки
     * отчёта. Включается флагом на время миграции; по исчерпании пачек джоб делает no-op, после чего
     * флаг выключается.
     */
    @Data
    public static class Backfill {
        /** Включён ли разовый backfill-джоб. По умолчанию выключен — включается только на миграцию. */
        private boolean reportsEnabled = false;
        /** Размер пачки сканов за один проход джоба (ограничивает память и длину транзакций). */
        @Min(1)
        private int batchSize = 100;
        /** Сколько раз пробовать один scan за жизнь процесса перед временным skip до рестарта. */
        @Min(1)
        private int maxAttempts = 3;
        /** Период между проходами джоба, мс (используется как {@code fixedDelayString}). */
        @Min(1)
        private long fixedDelayMs = 30_000;
    }

    @Data
    public static class GeoIp {
        @NotBlank
        private String dbPath = "classpath:ip-db/dbip-country-lite-2026-06.mmdb";
    }

    // Outbox-настройки (compliance.outbox.*) биндит OutboxProperties (compliance-messaging) —
    // единый источник для api и worker. Дублирующего worker-класса здесь больше нет.

    /**
     * Score-модель сайта (§5.5): {@code initial − Σ(basePoints[severity] × verificationWeight)}.
     * Вынесена в конфиг, чтобы тюнить риск-модель без пересборки и держать единой для worker и app.
     * Инварианты ({@link #isAllSeveritiesCovered()}, {@link #isWeightsInRange()}) — fail-fast.
     */
    @Data
    public static class Score {
        @Positive
        private int initial = 100;
        /**
         * Базовые очки по severity. Должны покрывать все значения {@link FindingSeverity}. Java-дефолты
         * совпадают с core-yml — properties самовалидны без yml (worker-IT биндят их напрямую). Заданный
         * в yml ключ переопределяет дефолт того же ключа (Spring мёржит в ту же мапу).
         */
        private Map<FindingSeverity, Integer> basePoints = defaultBasePoints();
        /**
         * Вес по verification-статусу (ключи — имена {@code VerificationStatus} + {@code DEFAULT}).
         * {@code DEFAULT} — безопасный фолбэк для UNVERIFIED, FALSE_POSITIVE, null и будущих
         * неизвестных статусов. Они не являются подтверждённым риском и не уменьшают score.
         */
        private Map<String, Double> verificationWeight = defaultVerificationWeight();
        /**
         * Потолок суммарного вычета очков по категории. Технические категории (SECURITY, COOKIES)
         * дают пачку findings (9 security-заголовков, набор cookie-флагов), которые иначе просадили
         * бы score сильнее core 152-ФЗ нарушений (DOCUMENTS/HOSTING/CONSENT). Cap ограничивает их
         * совокупный вклад. Категории без записи здесь не ограничиваются. Тюнится из yml.
         */
        private Map<FindingCategory, Integer> categoryCap = defaultCategoryCap();

        private static Map<FindingSeverity, Integer> defaultBasePoints() {
            Map<FindingSeverity, Integer> m = new EnumMap<>(FindingSeverity.class);
            m.put(FindingSeverity.CRITICAL, 30);
            m.put(FindingSeverity.HIGH, 20);
            m.put(FindingSeverity.MEDIUM, 12);
            m.put(FindingSeverity.LOW, 5);
            return m;
        }

        private static Map<String, Double> defaultVerificationWeight() {
            Map<String, Double> m = new java.util.LinkedHashMap<>();
            m.put("CONFIRMED", 1.00);
            m.put("DETECTED", 0.65);
            m.put("UNVERIFIED", 0.00);
            m.put("FALSE_POSITIVE", 0.00);
            m.put("DEFAULT", 0.00);
            return m;
        }

        private static Map<FindingCategory, Integer> defaultCategoryCap() {
            Map<FindingCategory, Integer> m = new EnumMap<>(FindingCategory.class);
            // Технические категории: совокупный вклад ограничен, чтобы пачка security-заголовков/
            // cookie-флагов не перевешивала core 152-ФЗ нарушения.
            m.put(FindingCategory.SECURITY, 20);
            m.put(FindingCategory.COOKIES, 15);
            return m;
        }

        /** Потолок вычета по категории или {@code null}, если категория не ограничена. */
        public Integer capFor(FindingCategory category) {
            return category == null ? null : categoryCap.get(category);
        }

        public int basePointsFor(FindingSeverity severity) {
            if (severity == null) {
                return 0;
            }
            return basePoints.getOrDefault(severity, 0);
        }

        /** Вес статуса; {@code null}/неизвестный → {@code DEFAULT} (обязан присутствовать). */
        public double weightFor(String statusName) {
            Double w = statusName == null ? null : verificationWeight.get(statusName);
            return w != null ? w : verificationWeight.getOrDefault("DEFAULT", 1.0);
        }

        @AssertTrue(message = "compliance.score.base-points должен покрывать все FindingSeverity "
                + "(CRITICAL/HIGH/MEDIUM/LOW)")
        public boolean isAllSeveritiesCovered() {
            for (FindingSeverity s : FindingSeverity.values()) {
                if (!basePoints.containsKey(s)) {
                    return false;
                }
            }
            return true;
        }

        @AssertTrue(message = "compliance.score.verification-weight должен содержать ключ DEFAULT, "
                + "а все веса быть в диапазоне [0.0, 1.0]")
        public boolean isWeightsInRange() {
            if (!verificationWeight.containsKey("DEFAULT")) {
                return false;
            }
            return verificationWeight.values().stream()
                    .allMatch(w -> w != null && w >= 0.0 && w <= 1.0);
        }
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

    /**
     * {@code total-deadline} — страховочный дедлайн поверх crawler-таймаута; он обязан быть не меньше
     * самого crawler-таймаута, иначе пайплайн помечал бы PARTIAL ещё живой краул (комментарий поля
     * это обещал, но без проверки это легко нарушить конфигом). Fail-fast на старте.
     */
    @AssertTrue(message = "compliance.scan.totalDeadline must be >= "
            + "compliance.crawler.crawlerTimeoutSeconds")
    public boolean isTotalDeadlineAboveCrawlerTimeout() {
        if (scan == null || scan.getTotalDeadline() == null || crawler == null) {
            return true;
        }
        return scan.getTotalDeadline().getSeconds() >= crawler.getCrawlerTimeoutSeconds();
    }

    /**
     * Динамически перекраулить нельзя больше страниц, чем накраулено статикой: {@code dynamic.maxPages}
     * не должен превышать {@code crawler.maxPages}. Иначе лимит dynamic-прохода — недостижимая ручка.
     */
    @AssertTrue(message = "compliance.crawler.dynamic.maxPages must be <= compliance.crawler.maxPages")
    public boolean isDynamicMaxPagesWithinCrawlerMaxPages() {
        if (crawler == null || crawler.getDynamic() == null) {
            return true;
        }
        return crawler.getDynamic().getMaxPages() <= crawler.getMaxPages();
    }
}
