package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Этап 13: разбор юрисдикции (синтаксис) отделён от проверки доступности (enabled-jurisdictions). */
class JurisdictionEnablementTest {

    private static final Set<ScanJurisdiction> ENABLED =
            EnumSet.of(ScanJurisdiction.RU, ScanJurisdiction.EU, ScanJurisdiction.DE);

    @Test
    void parseAcceptsKnownJurisdiction() {
        assertThat(ScanCommandService.parseJurisdiction("eu")).isEqualTo(ScanJurisdiction.EU);
    }

    @Test
    void parseRejectsUnknownJurisdiction() {
        assertThatThrownBy(() -> ScanCommandService.parseJurisdiction("ATLANTIS"))
                .isInstanceOf(ComplianceValidationException.class);
    }

    @Test
    void parseRejectsBlankJurisdiction() {
        assertThatThrownBy(() -> ScanCommandService.parseJurisdiction("  "))
                .isInstanceOf(ComplianceValidationException.class);
    }

    @Test
    void enabledJurisdictionPasses() {
        assertThat(ScanCommandService.parseAndAssertEnabled("DE", ENABLED))
                .isEqualTo(ScanJurisdiction.DE);
    }

    @Test
    void disabledButValidJurisdictionRejected() {
        // UK — синтаксически валидна, но не в enabled-jurisdictions → 400, а не пустой отчёт.
        assertThatThrownBy(() -> ScanCommandService.parseAndAssertEnabled("UK", ENABLED))
                .isInstanceOf(ComplianceValidationException.class)
                .hasMessageContaining("UK");
    }

    // ── locale (§ PLAN-evidence-localization, Этап 1) ───────────────────────────────────────────

    @Test
    void parseLocaleNormalizesAndDefaults() {
        assertThat(ScanCommandService.parseLocale("DE")).isEqualTo("de");   // регистр
        assertThat(ScanCommandService.parseLocale(" en ")).isEqualTo("en"); // trim
        assertThat(ScanCommandService.parseLocale(null)).isEqualTo("ru");   // пусто → дефолт
        assertThat(ScanCommandService.parseLocale("")).isEqualTo("ru");
        assertThat(ScanCommandService.parseLocale("klingon")).isEqualTo("ru"); // неизвестный → дефолт (мягко)
    }
}
