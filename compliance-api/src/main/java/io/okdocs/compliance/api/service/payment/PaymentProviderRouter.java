package io.okdocs.compliance.api.service.payment;

import io.okdocs.compliance.contracts.enums.PaymentProvider;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Маршрутизация «locale + (опционально) запрошенный провайдер» → платёжный провайдер
 * (docs/PLAN-payments.md). Закладывает multi-provider routing, но в этой итерации поддерживает
 * только {@code ru → YOOKASSA}. Неподдерживаемая комбинация → 400 (ComplianceValidationException).
 * <p>
 * Будущее (не сейчас): {@code ru → TELEGRAM/TON}, {@code en → STRIPE/TELEGRAM}.
 */
@Slf4j
@Component
public class PaymentProviderRouter {

    /** Доступные провайдеры по locale; первый в списке — дефолт. Порядок = приоритет показа. */
    private static final Map<String, List<PaymentProvider>> ROUTING = Map.of(
            "ru", List.of(PaymentProvider.YOOKASSA));

    /** Зарегистрированные адаптеры (только реализованные провайдеры). */
    private final Map<PaymentProvider, PaymentProviderAdapter> adapters = new EnumMap<>(PaymentProvider.class);

    public PaymentProviderRouter(List<PaymentProviderAdapter> adapterBeans) {
        for (PaymentProviderAdapter adapter : adapterBeans) {
            adapters.put(adapter.provider(), adapter);
        }
    }

    /**
     * Выбрать провайдера для locale. {@code requestedProvider == null} → дефолт locale.
     * Бросает {@link ComplianceValidationException} (→ 400), если комбинация не поддерживается
     * или для провайдера нет зарегистрированного адаптера.
     */
    public PaymentProvider resolve(String locale, PaymentProvider requestedProvider) {
        String normalized = normalizeLocale(locale);
        List<PaymentProvider> available = ROUTING.get(normalized);
        if (available == null || available.isEmpty()) {
            throw new ComplianceValidationException(
                    "Оплата для locale '" + normalized + "' пока не поддерживается");
        }
        PaymentProvider chosen = requestedProvider != null ? requestedProvider : available.get(0);
        if (!available.contains(chosen)) {
            throw new ComplianceValidationException(
                    "Провайдер " + chosen + " недоступен для locale '" + normalized + "'");
        }
        if (!adapters.containsKey(chosen)) {
            throw new ComplianceValidationException("Провайдер " + chosen + " пока не реализован");
        }
        return chosen;
    }

    /** Адаптер по провайдеру (после {@link #resolve}). */
    public PaymentProviderAdapter adapter(PaymentProvider provider) {
        PaymentProviderAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw new ComplianceValidationException("Провайдер " + provider + " пока не реализован");
        }
        return adapter;
    }

    private static String normalizeLocale(String rawLocale) {
        if (rawLocale == null || rawLocale.isBlank()) {
            return "ru";
        }
        String normalized = rawLocale.trim().toLowerCase(Locale.ROOT);
        int sep = normalized.indexOf('-');
        if (sep > 0) {
            normalized = normalized.substring(0, sep);
        }
        sep = normalized.indexOf('_');
        if (sep > 0) {
            normalized = normalized.substring(0, sep);
        }
        return normalized.isBlank() ? "ru" : normalized;
    }
}
