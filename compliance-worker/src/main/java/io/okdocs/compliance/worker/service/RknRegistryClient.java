package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.RegistryStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Проверка оператора в реестре Роскомнадзора (§5.5). Наполняет {@code registryStatus} в
 * {@link ScanAnalysisContext} ДО запуска RuleEngine; маппинг в {@code VerificationStatus} finding'а
 * делает {@link io.okdocs.compliance.rules.ru.NotInRknRegistryRule}.
 * <p>
 * ⏸ <b>Заглушка MVP</b>: реальный lookup в реестр РКН не реализован. Возвращаем
 * {@link RegistryStatus#LOOKUP_FAILED} — честное «не удалось проверить» (правило отдаст
 * {@code UNVERIFIED}, а не промолчит). Когда появится интеграция с реестром, заменить тело
 * {@link #lookup} на реальный запрос по домену/ИНН.
 */
@Slf4j
@Component
public class RknRegistryClient {

    /**
     * @param domain домен сайта (для будущего lookup по домену)
     * @param inn    ИНН оператора, если найден на страницах (nullable)
     * @return статус проверки в реестре
     */
    public RegistryStatus lookup(String domain, String inn) {
        log.debug("RKN registry lookup is stubbed (domain={}, inn={}) → LOOKUP_FAILED", domain, inn);
        return RegistryStatus.LOOKUP_FAILED;
    }
}
