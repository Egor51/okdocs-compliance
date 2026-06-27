package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.RuleFact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** EvidenceRenderer: structured-key+params рендерится по locale; fallback-контракт (§ Этап 2). */
class EvidenceRendererTest {

    private final EvidenceRenderer renderer = new EvidenceRenderer();

    private static RuleFact legacy(String evidence) {
        return new RuleFact("X", evidence, "https://s/", SourceType.HTML,
                EvidenceType.STATIC_ANALYSIS, null, null, VerificationStatus.DETECTED);
    }

    private static RuleFact structured(String key, Map<String, Object> params) {
        return new RuleFact("X", "PLAIN-FALLBACK", "https://s/", SourceType.HTML,
                EvidenceType.STATIC_ANALYSIS, null, null, VerificationStatus.DETECTED, key, params);
    }

    @Test
    void nullKeyReturnsPlainEvidence() {
        assertThat(renderer.render(legacy("На странице …"), "de")).isEqualTo("На странице …");
    }

    @Test
    void rendersRuTemplateWithParams() {
        var fact = structured("MISSING_HSTS", Map.of("page", "https://s/p"));
        assertThat(renderer.render(fact, "ru"))
                .isEqualTo("На странице https://s/p отсутствует заголовок Strict-Transport-Security.");
    }

    @Test
    void rendersEnTemplateWithParams() {
        var fact = structured("MISSING_HSTS", Map.of("page", "https://s/p"));
        assertThat(renderer.render(fact, "en"))
                .isEqualTo("The Strict-Transport-Security header is missing on https://s/p.");
    }

    @Test
    void unknownLocaleFallsBackToEn() {
        // it (нет bundle) → fallback на en.
        var fact = structured("MISSING_HSTS", Map.of("page", "p"));
        assertThat(renderer.render(fact, "it"))
                .isEqualTo("The Strict-Transport-Security header is missing on p.");
    }

    @Test
    void unknownKeyAnywhereFallsBackToPlain() {
        // Ключа нет ни в одном bundle → plain fallback (де-факто покрывает «нет шаблона»).
        var fact = structured("NO_SUCH_KEY_AT_ALL", Map.of("items", List.of("a", "b")));
        assertThat(renderer.render(fact, "de")).isEqualTo("PLAIN-FALLBACK");
    }

    @Test
    void listParamJoinedWithCommas() {
        var fact = structured("WEAK_CSP",
                Map.of("page", "p", "items", List.of("unsafe-inline", "wildcard")));
        assertThat(renderer.render(fact, "ru"))
                .isEqualTo("Content-Security-Policy на странице p содержит небезопасные директивы: "
                        + "unsafe-inline, wildcard.");
    }

    @Test
    void setParamJoinedWithCommas() {
        // params могут нести Set (cookie-правила) — рендерер джойнит любую Collection.
        var fact = structured("COOKIE_WITHOUT_SECURE_FLAG",
                Map.of("page", "p", "items", new java.util.LinkedHashSet<>(List.of("_ga", "sid"))));
        assertThat(renderer.render(fact, "ru")).isEqualTo("На странице p cookie без флага Secure: _ga, sid.");
    }

    @Test
    void unknownKeyFallsBackToPlain() {
        var fact = structured("NO_SUCH_KEY", Map.of());
        assertThat(renderer.render(fact, "ru")).isEqualTo("PLAIN-FALLBACK");
    }

    // ── renderMessage (RuleOutcome.message, Этап 4) ─────────────────────────────────────────────

    @Test
    void renderMessageEn() {
        assertThat(renderer.renderMessage("NOT_EVALUATED_NO_PAGES", Map.of(), "RU-PLAIN", "en"))
                .isEqualTo("The rule was not evaluated: the crawler returned no pages.");
    }

    @Test
    void renderMessageRu() {
        assertThat(renderer.renderMessage("NOT_EVALUATED_NO_INPUT", Map.of(), "RU-PLAIN", "ru"))
                .isEqualTo("Правило не проверялось: нет входных данных для проверки.");
    }

    @Test
    void renderMessageNullKeyReturnsFallback() {
        assertThat(renderer.renderMessage(null, Map.of(), "RU-PLAIN", "en")).isEqualTo("RU-PLAIN");
    }

    @Test
    void renderMessageUnknownLocaleFallsBackToEn() {
        assertThat(renderer.renderMessage("NOT_EVALUATED_RULE_ERROR", Map.of(), "RU-PLAIN", "fr"))
                .isEqualTo("The rule was not evaluated due to an execution error.");
    }

    // ── EU/overlay keys (Этап 5): cmp-suffix + per-variant + items ──────────────────────────────

    @Test
    void rejectAbsentWithCmpSuffix() {
        var fact = structured("NO_REJECT_ABSENT", Map.of("cmp", " CMP: OneTrust."));
        assertThat(renderer.render(fact, "en"))
                .isEqualTo("Banner has an accept option but no reject option. CMP: OneTrust.");
    }

    @Test
    void rejectAbsentWithEmptyCmp() {
        var fact = structured("NO_REJECT_ABSENT", Map.of("cmp", ""));
        assertThat(renderer.render(fact, "en"))
                .isEqualTo("Banner has an accept option but no reject option.");
    }

    @Test
    void trackersAfterRejectJoinsItems() {
        var fact = structured("TRACKERS_AFTER_REJECT", Map.of("items", List.of("Google", "Meta")));
        assertThat(renderer.render(fact, "en"))
                .isEqualTo("Trackers still loading after consent rejection: Google, Meta.");
    }

    @Test
    void euOverlayKeyFallsBackToEnForGermanLocale() {
        // de-шаблона нет → fallback на en (для немца пока английский, до de-шаблонов).
        var fact = structured("DE_TDDDG_TERMINAL_ACCESS_WITHOUT_CONSENT",
                Map.of("items", List.of("cookie:_ga")));
        assertThat(renderer.render(fact, "de"))
                .isEqualTo("Non-essential storage/access after consent rejection: cookie:_ga.");
    }
}
