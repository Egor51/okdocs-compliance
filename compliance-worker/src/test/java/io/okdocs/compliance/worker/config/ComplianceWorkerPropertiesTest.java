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

    /** Минимальный валидный набор (всё остальное — дефолты). */
    private Map<String, String> baseValid() {
        Map<String, String> p = new HashMap<>();
        p.put("compliance.crawler.crawler-timeout-seconds", "90");
        p.put("compliance.scan.stale-after", "5m");
        return p;
    }
}
