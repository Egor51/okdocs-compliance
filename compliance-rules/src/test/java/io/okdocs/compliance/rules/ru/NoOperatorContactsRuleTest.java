package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.rules.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoOperatorContactsRuleTest {

    private final NoOperatorContactsRule rule = new NoOperatorContactsRule();

    private static PageAnalysisResult pageWithText(String text) {
        return TestFixtures.page("https://site.ru", text, false,
                List.of(), List.of(), List.of(), "<html></html>");
    }

    @Test
    void flagsWhenNoOperatorRequisites() {
        assertThat(rule.evaluate(TestFixtures.ctx(pageWithText("Просто текст без реквизитов"))))
                .singleElement()
                .satisfies(f -> assertThat(f.code()).isEqualTo("NO_OPERATOR_CONTACTS"));
    }

    @Test
    void silentWhenInnPresent() {
        assertThat(rule.evaluate(TestFixtures.ctx(
                pageWithText("ООО Пример, ИНН 7701234567")))).isEmpty();
    }

    @Test
    void silentWhenOgrnPresent() {
        assertThat(rule.evaluate(TestFixtures.ctx(
                pageWithText("ОГРН 1027700132195, г. Москва")))).isEmpty();
    }

    @Test
    void silentWhenNoPages() {
        assertThat(rule.evaluate(TestFixtures.ctx())).isEmpty();
    }
}
