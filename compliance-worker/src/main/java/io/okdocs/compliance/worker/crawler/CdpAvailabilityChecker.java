package io.okdocs.compliance.worker.crawler;

import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runtime-проверка платного premium-потока на старте (§5.4). Property-валидация
 * ({@link ComplianceWorkerProperties.Dynamic#isPremiumRequiresCdp()}) гарантирует только, что CDP
 * <em>сконфигурирован</em> (enabled + base-url), но не что он <em>живой</em>: эндпоинт может быть
 * недоступен, auth неверный, {@code /json/version} не отвечать 200. Без рантайм-проверки такой
 * деплой стартует «зелёным», а каждый premium-скан падает в FAILED+refund — обнаруживается только
 * на первом реальном платеже.
 * <p>
 * Поэтому при {@code premium-enabled=true} после готовности контекста дёргаем
 * {@link DynamicCrawler#isAvailable()} и роняем приложение ({@link IllegalStateException}), если CDP
 * недоступен. {@code premium-enabled=false} (локаль/стейдж) проверку пропускает.
 * <p>
 * На {@link ApplicationReadyEvent}, а не в конструкторе: бины уже собраны, а исключение здесь
 * приводит к завершению Spring Boot с ненулевым кодом — worker/app не остаётся «живым» с мёртвым
 * платным потоком.
 */
@Slf4j
@Component
public class CdpAvailabilityChecker {

    private final ComplianceWorkerProperties properties;
    private final DynamicCrawler dynamicCrawler;

    public CdpAvailabilityChecker(ComplianceWorkerProperties properties, DynamicCrawler dynamicCrawler) {
        this.properties = properties;
        this.dynamicCrawler = dynamicCrawler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifyPremiumCdp() {
        if (!properties.getCrawler().getDynamic().isPremiumEnabled()) {
            log.info("Premium CDP disabled (premium-enabled=false); skipping availability check");
            return;
        }
        if (!dynamicCrawler.isAvailable()) {
            throw new IllegalStateException(
                    "compliance.crawler.dynamic.premium-enabled=true, но CDP-эндпоинт недоступен "
                            + "(/json/version не ответил 200 — эндпоинт мёртв, неверный auth-token или "
                            + "сетевая изоляция). Каждый CABINET_PREMIUM-скан падал бы в FAILED+refund. "
                            + "Почините CDP (base-url/auth-token) или выставьте premium-enabled=false.");
        }
        log.info("Premium CDP available; premium scan flow is live");
    }
}
