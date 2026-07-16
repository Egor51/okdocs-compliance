package io.okdocs.compliance.rules;

import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.JurisdictionLayer;

import java.util.List;
import java.util.Set;

/**
 * Правило проверки соответствия. Чистая функция {@code ctx → facts}: получает наполненный
 * worker'ом {@link ScanAnalysisContext} (включая enrichment-данные) и возвращает наблюдения.
 * Без Spring/JPA — правила тестируются без контекста.
 */
public interface Rule {

    RuleDefinition definition();

    /**
     * Слои правовых требований, к которым применимо правило. {@link RuleEngine} запускает правило,
     * если этот набор пересекается со слоями скана
     * ({@link io.okdocs.compliance.contracts.enums.JurisdictionProfiles#layers}). Так common
     * EU-правило ({@code {EU}}) работает на сканах EU/DE/FR/ES.
     * <p>
     * Дефолт совместим с одно-юрисдикционной моделью: один слой, выведённый из
     * {@link RuleDefinition#jurisdiction()}. RU-правила его не переопределяют. EU/common/overlay-
     * правила, поддерживающие несколько слоёв, переопределяют метод явным набором.
     */
    default Set<JurisdictionLayer> supportedLayers() {
        return Set.of(JurisdictionLayer.valueOf(definition().jurisdiction().name()));
    }

    List<RuleFact> evaluate(ScanAnalysisContext ctx);

    /**
     * Применимо ли правило к данному контексту — есть ли у него входные данные для проверки.
     * По умолчанию {@code true} (правилу всегда есть что анализировать на странице).
     * <p>
     * Правила, зависящие от данных, которые могут отсутствовать (cookies/storage — только при
     * DYNAMIC-рендере; TLS-снимок — может не сняться), переопределяют метод: если данных нет, правило
     * НЕ должно давать «проверка пройдена» (PASSED) — это вводит в заблуждение («не нашли нарушений»
     * там, где просто не проверяли). {@link RuleEngine} при {@code appliesTo == false} помечает
     * правило {@code NOT_EVALUATED}, а не PASSED/FAILED.
     */
    default boolean appliesTo(ScanAnalysisContext ctx) {
        return true;
    }

    /**
     * Расширенный вариант {@link #appliesTo(ScanAnalysisContext)} с машиночитаемой причиной.
     * Старые правила автоматически сохраняют прежнее поведение.
     */
    default RuleApplicability applicability(ScanAnalysisContext ctx) {
        return appliesTo(ctx)
                ? RuleApplicability.available()
                : RuleApplicability.unavailable(
                        "Правило не проверялось: нет входных данных для проверки.",
                        "NOT_EVALUATED_NO_INPUT");
    }
}
