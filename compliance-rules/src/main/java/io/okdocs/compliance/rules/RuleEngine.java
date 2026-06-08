package io.okdocs.compliance.rules;

import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Запускает правила над одним {@link ScanAnalysisContext}, отбирая только те, чья
 * {@link RuleDefinition#jurisdiction()} совпадает с {@link ScanAnalysisContext#jurisdiction()}
 * скана: RU-скан не прогоняется по GDPR-правилам и наоборот. Изоляция отказов: исключение
 * отдельного правила собирается в {@link RuleEngineResult#errors}, а не валит весь анализ — один
 * битый rule не лишает отчёта остальных находок. Без Spring.
 */
public final class RuleEngine {

    private final List<Rule> rules;

    public RuleEngine(List<Rule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    public RuleEngineResult evaluate(ScanAnalysisContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        List<RuleFact> facts = new ArrayList<>();
        List<RuleEvaluationError> errors = new ArrayList<>();

        for (Rule rule : rules) {
            // Резолвим code ДО evaluate: если падает не evaluate, а definition(), мы всё равно
            // должны зарегистрировать ошибку, а не уронить движок. Fallback — имя класса правила.
            String code = resolveCode(rule);

            // Юрисдикционный гейт: правило другой юрисдикции пропускаем молча (это не ошибка).
            // Если definition() сломан и юрисдикцию не прочитать — правило неклассифицируемо,
            // запускать его на чужом скане небезопасно: фиксируем ошибку и пропускаем.
            ScanJurisdiction ruleJurisdiction;
            try {
                ruleJurisdiction = rule.definition().jurisdiction();
            } catch (RuntimeException e) {
                errors.add(new RuleEvaluationError(code, e.getClass().getSimpleName(), e.getMessage()));
                continue;
            }
            if (ruleJurisdiction != ctx.jurisdiction()) {
                continue;
            }

            try {
                List<RuleFact> ruleFacts = rule.evaluate(ctx);
                if (ruleFacts != null) {
                    facts.addAll(ruleFacts);
                }
            } catch (RuntimeException e) {
                errors.add(new RuleEvaluationError(
                        code,
                        e.getClass().getSimpleName(),
                        e.getMessage()));
            }
        }

        return new RuleEngineResult(List.copyOf(facts), List.copyOf(errors));
    }

    /** code из definition(); если definition() сам бросает — fallback на имя класса правила. */
    private static String resolveCode(Rule rule) {
        try {
            RuleDefinition def = rule.definition();
            if (def != null && def.code() != null) {
                return def.code();
            }
        } catch (RuntimeException ignored) {
            // definition() сломан — используем имя класса ниже
        }
        return rule.getClass().getSimpleName();
    }
}
