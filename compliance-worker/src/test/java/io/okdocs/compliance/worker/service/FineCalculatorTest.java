package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FineCalculatorTest {

    private final FineCalculator calculator = new FineCalculator();

    private static ComplianceFinding withFine(String fineAmount) {
        ComplianceFinding f = new ComplianceFinding();
        f.setFineAmount(fineAmount);
        return f;
    }

    @Test
    void parsesMinMaxAcrossAllNumbers() {
        // Реальный формат правила: несколько диапазонов в свободном тексте.
        long[] range = calculator.parse("10 000 – 20 000 ₽ для ИП, 30 000 – 60 000 ₽ для юрлиц");
        assertThat(range[0]).isEqualTo(10_000);
        assertThat(range[1]).isEqualTo(60_000);
    }

    @Test
    void dropsNumbersBelowThousand() {
        // Маленькие числа (<1000) отбрасываются: "5.39" из ссылки на статью не попадёт в диапазон.
        // (NB: regex \d[\d\s]* склеивает группы через пробел, поэтому ссылки на статьи отделяем
        // не-пробельными символами — как в реальных fineAmount-строках правил.)
        long[] range = calculator.parse("ст.5.39 КоАП; штраф 300000 ₽");
        assertThat(range[0]).isEqualTo(300_000);
        assertThat(range[1]).isEqualTo(300_000);
    }

    @Test
    void nullOrBlankGivesZeroRange() {
        assertThat(calculator.parse(null)).containsExactly(0, 0);
        assertThat(calculator.parse("  ")).containsExactly(0, 0);
        assertThat(calculator.parse("без числовых данных")).containsExactly(0, 0);
    }

    @Test
    void totalRangeSumsAcrossFindings() {
        var findings = List.of(
                withFine("10 000 – 20 000 ₽"),
                withFine("30 000 – 60 000 ₽"));
        // min: 10000+30000=40000, max: 20000+60000=80000
        String total = calculator.totalRange(findings);
        assertThat(total).isEqualTo("от 40 000 до 80 000 ₽");
    }

    @Test
    void totalRangeZeroWhenNoFines() {
        assertThat(calculator.totalRange(List.of(withFine(null), withFine("ст. 5.39")))).isEqualTo("0 ₽");
    }

    @Test
    void totalRangeUsesNonBreakingFriendlyGrouping() {
        // 1 000 000 группируется пробелами как разделителем тысяч.
        String total = calculator.totalRange(List.of(withFine("1 000 000 ₽")));
        assertThat(total).isEqualTo("от 1 000 000 до 1 000 000 ₽");
    }
}
