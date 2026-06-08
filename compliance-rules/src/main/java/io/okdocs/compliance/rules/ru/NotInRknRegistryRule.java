package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.RegistryStatus;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleDefinition;
import io.okdocs.compliance.rules.RuleFact;

import java.util.List;

/**
 * Оператор не найден / не подтверждён в реестре Роскомнадзора. В okdocks это была заглушка
 * (всегда пусто); здесь правило реальное и работает поверх enrichment-поля
 * {@link ScanAnalysisContext#registryStatus()}. Маппинг {@link RegistryStatus} → {@link VerificationStatus}
 * (PLAN.md §1.1/§3.2):
 * <ul>
 *   <li>{@code NOT_FOUND} → finding, {@code CONFIRMED} (оператора в реестре нет);</li>
 *   <li>{@code LOOKUP_FAILED} → finding, {@code UNVERIFIED} (реестр недоступен — не молчим, а
 *       честно говорим «не удалось проверить»);</li>
 *   <li>{@code FOUND} / {@code null} → finding не создаётся.</li>
 * </ul>
 * Метаданные перенесены из MVP (okdocks {@code NOT_IN_RKN_REGISTRY}).
 */
public final class NotInRknRegistryRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "RKN_REGISTRY_NOT_VERIFIED",
            ScanJurisdiction.RU,
            FindingSeverity.HIGH,
            FindingCategory.DOCUMENTS,
            "Оператор не найден в реестре Роскомнадзора",
            "Потенциально 100 000 – 300 000 ₽ для ИП и юрлиц при подтверждении обязанности "
                    + "уведомления по ст. 22 152-ФЗ",
            "ст. 22 152-ФЗ (с учётом исключений ч. 2), ст. 13.11 ч. 10 КоАП РФ",
            "Оператор персональных данных до начала обработки обязан направить уведомление в "
                    + "Роскомнадзор, если не применимы исключения ч. 2 ст. 22 152-ФЗ. Отсутствие записи в "
                    + "реестре может указывать на риск неподачи уведомления. Автоматическая проверка реестра "
                    + "не является окончательным доказательством и требует ручной верификации оснований "
                    + "обработки и применимости исключений.",
            "1. Проверьте наличие записи в реестре Роскомнадзора по ИНН, ОГРН, ОГРНИП и наименованию. "
                    + "2. Проверьте, применимы ли исключения из ч. 2 ст. 22 152-ФЗ. 3. При отсутствии "
                    + "оснований для исключений подготовьте и направьте уведомление. 4. Сохраните "
                    + "подтверждения отправки и приёма уведомлений.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        RegistryStatus status = ctx.registryStatus();
        if (status == null || status == RegistryStatus.FOUND) {
            return List.of();
        }

        String inn = RuPatterns.parseInn(ctx).orElse(null);
        return switch (status) {
            case NOT_FOUND -> List.of(fact(
                    "Оператор не найден в реестре Роскомнадзора"
                            + (inn == null ? "." : " (проверка по ИНН " + inn + ")."),
                    "rkn-registry-not-found" + (inn == null ? "" : ";inn=" + inn),
                    VerificationStatus.CONFIRMED));
            case LOOKUP_FAILED -> List.of(fact(
                    "Не удалось проверить оператора в реестре Роскомнадзора: реестр недоступен. "
                            + "Требуется ручная проверка.",
                    "rkn-registry-lookup-failed",
                    VerificationStatus.UNVERIFIED));
            case FOUND -> List.of();
        };
    }

    private RuleFact fact(String evidence, String signals, VerificationStatus verification) {
        return new RuleFact(
                DEFINITION.code(), evidence, null, SourceType.HTML,
                io.okdocs.compliance.contracts.enums.EvidenceType.STATIC_ANALYSIS,
                null, signals, verification);
    }
}
