package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.rules.Rule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RulesConfigurationTest {

    @Test
    void activeRuRulesHavePositiveOutcomeMetadata() {
        var config = new RulesConfiguration();

        for (Rule rule : config.ruRules()) {
            var definition = rule.definition();
            assertThat(definition.positiveTitle())
                    .as("%s positiveTitle", definition.code())
                    .isNotBlank();
            assertThat(definition.positiveMessage())
                    .as("%s positiveMessage", definition.code())
                    .isNotBlank();
        }
    }
}
