package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleEngine;
import io.okdocs.compliance.rules.ru.ConsentDefaultCheckedRule;
import io.okdocs.compliance.rules.ru.CrossBorderTransferRule;
import io.okdocs.compliance.rules.ru.NoCookieConsentRule;
import io.okdocs.compliance.rules.ru.NoOperatorContactsRule;
import io.okdocs.compliance.rules.ru.NoPrivacyPolicyRule;
import io.okdocs.compliance.rules.ru.NonRuHostingRule;
import io.okdocs.compliance.rules.ru.NotInRknRegistryRule;
import io.okdocs.compliance.rules.ru.ThirdPartyTrackersRule;
import io.okdocs.compliance.rules.ru.TrackersBeforeConsentRule;
import io.okdocs.compliance.rules.ru.UnprotectedDataFormsRule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Сборка правил (§5.1). Правила в {@code compliance-rules} НЕ аннотированы {@code @Component}
 * (модуль без Spring), поэтому autowire {@code List<Rule>} собрал бы пустой список — бины
 * создаются вручную явной фабрикой. Один список вместо {@code @Bean} на правило: легко
 * фильтровать по юрисдикции (ru/eu) и держать {@code compliance-rules} чистым от Spring.
 */
@Configuration
public class RulesConfiguration {

    @Bean
    List<Rule> ruRules() {
        return List.of(
                new NoPrivacyPolicyRule(), new UnprotectedDataFormsRule(),
                new ConsentDefaultCheckedRule(), new NoCookieConsentRule(),
                new ThirdPartyTrackersRule(), new CrossBorderTransferRule(),
                new NoOperatorContactsRule(), new NotInRknRegistryRule(),
                new NonRuHostingRule(), new TrackersBeforeConsentRule());
    }

    @Bean
    RuleEngine ruleEngine(List<Rule> ruRules) {
        return new RuleEngine(ruRules);
    }
    
}
