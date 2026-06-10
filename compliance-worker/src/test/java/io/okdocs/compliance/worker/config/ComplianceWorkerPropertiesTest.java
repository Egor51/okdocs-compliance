package io.okdocs.compliance.worker.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.validation.annotation.Validated;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Валидация {@link ComplianceWorkerProperties}: дефолты валидны, нарушения JSR-380 и кросс-полевого
 * инварианта (reaper-порог > crawler-таймаут) роняют биндинг (fail-fast на старте контекста).
 */
class ComplianceWorkerPropertiesTest {

    private ComplianceWorkerProperties bind(Map<String, String> props) {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(props);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        Binder binder = new Binder(source);
        var validationHandler =
                new org.springframework.boot.context.properties.bind.validation.ValidationBindHandler(validator);
        return binder.bind("compliance",
                        Bindable.of(ComplianceWorkerProperties.class)
                                .withAnnotations(ComplianceWorkerProperties.class.getAnnotation(Validated.class)),
                        validationHandler)
                .orElseThrow(() -> new IllegalStateException("bind returned empty"));
    }

    @Test
    void defaults_areValid() {
        ComplianceWorkerProperties props = bind(baseValid());
        assertThat(props.getCrawler().getMaxPages()).isEqualTo(20);
        assertThat(props.getCrawler().isRespectRobots()).isTrue();
        assertThat(props.getCrawler().getUserAgent()).contains("OkDocsCompliance");
        assertThat(props.getScan().getStaleAfter().toMinutes()).isEqualTo(5);
    }

    @Test
    void negativeMaxPages_failsValidation() {
        Map<String, String> p = baseValid();
        p.put("compliance.crawler.max-pages", "0");

        assertThatThrownBy(() -> bind(p))
                .isInstanceOfAny(BindException.class, BindValidationException.class);
    }

    @Test
    void blankUserAgent_failsValidation() {
        Map<String, String> p = baseValid();
        p.put("compliance.crawler.user-agent", "");

        assertThatThrownBy(() -> bind(p))
                .isInstanceOfAny(BindException.class, BindValidationException.class);
    }

    @Test
    void reaperThresholdNotAboveCrawlerTimeout_failsCrossFieldInvariant() {
        // staleAfter <= crawlerTimeoutSeconds — reaper убивал бы живой скан → инвариант падает.
        Map<String, String> p = baseValid();
        p.put("compliance.crawler.crawler-timeout-seconds", "120");
        p.put("compliance.scan.stale-after", "90s");

        // Биндинг падает с BindException; конкретное сообщение инварианта — в причинной цепочке.
        assertThatThrownBy(() -> bind(p))
                .isInstanceOfAny(BindException.class, BindValidationException.class)
                .hasStackTraceContaining("staleAfter");
    }

    @Test
    void reaperThresholdAboveCrawlerTimeout_passes() {
        Map<String, String> p = baseValid();
        p.put("compliance.crawler.crawler-timeout-seconds", "90");
        p.put("compliance.scan.stale-after", "5m");

        ComplianceWorkerProperties props = bind(p);
        assertThat(props.getScan().getStaleAfter().toSeconds()).isEqualTo(300);
    }

    @Test
    void totalDeadlineBelowCrawlerTimeout_failsCrossFieldInvariant() {
        // total-deadline — страховка ПОВЕРХ crawler-таймаута; меньше него → PARTIAL на живом крауле.
        Map<String, String> p = baseValid();
        p.put("compliance.crawler.crawler-timeout-seconds", "90");
        p.put("compliance.scan.total-deadline", "30s");

        assertThatThrownBy(() -> bind(p))
                .isInstanceOfAny(BindException.class, BindValidationException.class)
                .hasStackTraceContaining("totalDeadline");
    }

    @Test
    void dynamicMaxPagesAboveCrawlerMaxPages_failsCrossFieldInvariant() {
        // dynamic.max-pages не может превышать crawler.max-pages — иначе лимит недостижим.
        Map<String, String> p = baseValid();
        p.put("compliance.crawler.max-pages", "10");
        p.put("compliance.crawler.dynamic.max-pages", "20");

        assertThatThrownBy(() -> bind(p))
                .isInstanceOfAny(BindException.class, BindValidationException.class)
                .hasStackTraceContaining("maxPages");
    }

    @Test
    void scorePartialOverride_mergesWithDefaults() {
        // Spring Binder МЁРЖИТ в существующую дефолтную мапу (мутабельный геттер), а не заменяет её:
        // присланный ключ переопределяет дефолт, остальные severity сохраняют дефолтные очки. Поэтому
        // yml-override отдельного балла безопасен и не оставляет пробелов в покрытии (инвариант
        // isAllSeveritiesCovered защищает программно-опустошённую мапу, недостижимую через биндинг).
        Map<String, String> p = baseValid();
        p.put("compliance.score.base-points.CRITICAL", "40"); // переопределяем только CRITICAL

        ComplianceWorkerProperties props = bind(p);
        assertThat(props.getScore().basePointsFor(
                io.okdocs.compliance.contracts.enums.FindingSeverity.CRITICAL)).isEqualTo(40);
        assertThat(props.getScore().basePointsFor(
                io.okdocs.compliance.contracts.enums.FindingSeverity.LOW)).isEqualTo(5); // дефолт цел
    }

    @Test
    void scoreWeightOutOfRange_failsValidation() {
        Map<String, String> p = baseValid();
        p.put("compliance.score.verification-weight.CONFIRMED", "1.5"); // >1.0

        assertThatThrownBy(() -> bind(p))
                .isInstanceOfAny(BindException.class, BindValidationException.class)
                .hasStackTraceContaining("verification-weight");
    }

    @Test
    void scoreDefaults_areValid() {
        // Java-дефолты score-модели должны совпадать с эталоном и проходить инварианты.
        ComplianceWorkerProperties props = bind(baseValid());
        assertThat(props.getScore().getInitial()).isEqualTo(100);
        assertThat(props.getScore().basePointsFor(
                io.okdocs.compliance.contracts.enums.FindingSeverity.CRITICAL)).isEqualTo(30);
        assertThat(props.getScore().weightFor("DETECTED")).isEqualTo(0.65);
        assertThat(props.getScore().weightFor(null)).isEqualTo(0.80); // DEFAULT-фолбэк
    }

    @Test
    void premiumEnabledWithoutCdp_failsCrossFieldInvariant() {
        // premium обещан, но CDP не сконфигурирован → все premium-сканы были бы FAILED+refund.
        // Инвариант роняет старт, а не тихо ломает платный поток.
        Map<String, String> p = baseValid();
        p.put("compliance.crawler.dynamic.premium-enabled", "true");
        p.put("compliance.crawler.dynamic.enabled", "false");

        assertThatThrownBy(() -> bind(p))
                .isInstanceOfAny(BindException.class, BindValidationException.class)
                .hasStackTraceContaining("premium-enabled");
    }

    @Test
    void premiumEnabledWithBlankBaseUrl_failsCrossFieldInvariant() {
        // enabled=true, но base-url пустой — CDP всё равно недоступен → инвариант падает.
        Map<String, String> p = baseValid();
        p.put("compliance.crawler.dynamic.premium-enabled", "true");
        p.put("compliance.crawler.dynamic.enabled", "true");
        p.put("compliance.crawler.dynamic.base-url", "");

        assertThatThrownBy(() -> bind(p))
                .isInstanceOfAny(BindException.class, BindValidationException.class)
                .hasStackTraceContaining("premium-enabled");
    }

    @Test
    void premiumEnabledWithConfiguredCdp_passes() {
        Map<String, String> p = baseValid();
        p.put("compliance.crawler.dynamic.premium-enabled", "true");
        p.put("compliance.crawler.dynamic.enabled", "true");
        p.put("compliance.crawler.dynamic.base-url", "http://browserless:3000");

        ComplianceWorkerProperties props = bind(p);
        assertThat(props.getCrawler().getDynamic().isPremiumEnabled()).isTrue();
        assertThat(props.getCrawler().getDynamic().getBaseUrl()).isEqualTo("http://browserless:3000");
    }

    @Test
    void wsBaseUrl_failsHttpSchemeInvariant() {
        // base-url — HTTP CDP-эндпоинт (/json/version), а не WebSocket. ws:// ломает discovery
        // таргетов → инвариант падает на старте, а не глухим FAILED каждого premium-скана.
        Map<String, String> p = baseValid();
        p.put("compliance.crawler.dynamic.enabled", "true");
        p.put("compliance.crawler.dynamic.base-url", "ws://browserless:3000");

        assertThatThrownBy(() -> bind(p))
                .isInstanceOfAny(BindException.class, BindValidationException.class)
                .hasStackTraceContaining("base-url");
    }

    @Test
    void httpsBaseUrl_passesHttpSchemeInvariant() {
        Map<String, String> p = baseValid();
        p.put("compliance.crawler.dynamic.premium-enabled", "true");
        p.put("compliance.crawler.dynamic.enabled", "true");
        p.put("compliance.crawler.dynamic.base-url", "https://browserless.internal:3000");

        ComplianceWorkerProperties props = bind(p);
        assertThat(props.getCrawler().getDynamic().getBaseUrl())
                .isEqualTo("https://browserless.internal:3000");
    }

    @Test
    void premiumDisabled_passesWithoutCdp() {
        // Локаль/стейдж без платного потока: premium-enabled=false — старт проходит без CDP.
        Map<String, String> p = baseValid(); // baseValid уже ставит premium-enabled=false
        ComplianceWorkerProperties props = bind(p);
        assertThat(props.getCrawler().getDynamic().isPremiumEnabled()).isFalse();
    }

    /** Минимальный валидный набор (всё остальное — дефолты). Premium выключен: без CDP старт валиден. */
    private Map<String, String> baseValid() {
        Map<String, String> p = new HashMap<>();
        p.put("compliance.crawler.crawler-timeout-seconds", "90");
        p.put("compliance.scan.stale-after", "5m");
        p.put("compliance.crawler.dynamic.premium-enabled", "false");
        return p;
    }
}
