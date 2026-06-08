package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Резолвер метаданных правила по {@code code} для {@link FindingAssembler}.
 * <p>
 * ⏸ <b>MVP без БД-override</b>: возвращает дефолт из кода ({@link RuleDefinition}). Слияние с
 * {@code rule_config} (БД-override, §2.10, V010) отложено вместе с admin-редактированием правил —
 * это точка расширения. Когда override включится, здесь добавится чтение
 * {@code RuleMetadataOverrideRepository} в кэш и наложение поверх дефолта; интерфейс метода
 * ({@code code → RuleDefinition}) при этом не меняется.
 * <p>
 * <b>Код = источник истины о существовании правила</b>: список даёт {@link RulesConfiguration}
 * ({@code List<Rule>}). Факт с {@code code}, которого нет в коде, метаданных не получит (assembler
 * его отбросит).
 */
@Component
public class RuleMetadataResolver {

    private final Map<String, RuleDefinition> definitionsByCode;

    public RuleMetadataResolver(List<Rule> rules) {
        this.definitionsByCode = rules.stream()
                .map(Rule::definition)
                .collect(Collectors.toMap(RuleDefinition::code, Function.identity(), (a, b) -> a));
    }

    /** Метаданные правила по коду; empty, если кода нет в коде (мёртвый факт). */
    public Optional<RuleDefinition> resolve(String code) {
        return Optional.ofNullable(definitionsByCode.get(code));
    }

    /** Включено ли правило. MVP: все правила из кода включены (БД-override отложен). */
    public boolean isEnabled(String code) {
        return definitionsByCode.containsKey(code);
    }
}
