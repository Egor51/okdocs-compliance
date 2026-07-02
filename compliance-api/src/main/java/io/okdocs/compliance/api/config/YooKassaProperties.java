package io.okdocs.compliance.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Конфигурация доступа к YooKassa (prefix {@code yookassa}). В этой итерации — один магазин;
 * мультишоп добавится позже (см. docs/PLAN-payments.md). Credentials берутся только из env/config.
 *
 * @param apiBaseUrl         базовый URL API YooKassa
 * @param shopId             идентификатор магазина
 * @param secretKey          секретный ключ магазина
 * @param returnUrl          дефолтный URL возврата после оплаты (если запрос не передал свой)
 * @param allowedReturnHosts allowlist хостов для override {@code returnUrl} из запроса (анти-open-redirect)
 * @param testMode           флаг тестового режима (передаётся в платёж)
 * @param vatCode            код ставки НДС для чека (54-ФЗ): 1 — без НДС, 2 — 10%, 4 — 20%
 * @param taxSystemCode      код системы налогообложения для чека ({@code null} если не требуется)
 */
@ConfigurationProperties(prefix = "yookassa")
public record YooKassaProperties(
        String apiBaseUrl,
        String shopId,
        String secretKey,
        String returnUrl,
        List<String> allowedReturnHosts,
        Boolean testMode,
        Integer vatCode,
        Integer taxSystemCode
) {

    public YooKassaProperties {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = "https://api.yookassa.ru/v3";
        }
        if (returnUrl == null || returnUrl.isBlank()) {
            returnUrl = "http://localhost:3000/payment/success";
        }
        allowedReturnHosts = allowedReturnHosts == null ? List.of() : List.copyOf(allowedReturnHosts);
        if (testMode == null) {
            testMode = false;
        }
        if (vatCode == null) {
            vatCode = 1;
        }
    }

    public boolean isConfigured() {
        return StringUtils.hasText(shopId) && StringUtils.hasText(secretKey);
    }
}
