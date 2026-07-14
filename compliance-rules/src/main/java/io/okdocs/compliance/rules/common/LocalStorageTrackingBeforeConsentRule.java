package io.okdocs.compliance.rules.common;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.JurisdictionLayer;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleDefinition;
import io.okdocs.compliance.rules.RuleFact;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Трекинговые данные в Web Storage (localStorage) до получения согласия. Современные трекеры всё чаще
 * хранят идентификаторы не в cookie, а в localStorage, обходя cookie-баннеры. Детекция по ключам
 * localStorage, наблюдённым до взаимодействия с баннером (DYNAMIC через CDP), чьи имена матчат
 * {@link TrackerCookieNames}. Детектор подтверждает наличие известного ключа; правовое основание
 * обработки оценивается отдельно для выбранной юрисдикции.
 */
public final class LocalStorageTrackingBeforeConsentRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "LOCAL_STORAGE_TRACKING_BEFORE_CONSENT",
            ScanJurisdiction.RU,
            FindingSeverity.MEDIUM,
            FindingCategory.COOKIES,
            "Трекинговые данные в localStorage наблюдаются до выбора пользователя",
            null,
            "ст. 6, ст. 9 (если используется согласие), ст. 15 и ст. 18.1 152-ФЗ",
            "В Web Storage (localStorage) до взаимодействия с cookie-баннером уже присутствуют ключи "
                    + "аналитических/маркетинговых сервисов. Хранение трекинговых идентификаторов в "
                    + "localStorage требует такой же оценки цели, идентифицируемости и основания, как cookie; "
                    + "для RU сам технический факт не равен автоматически установленному нарушению.",
            "1. Если основанием служит согласие, не записывайте трекинговые идентификаторы до выбора. 2. Инициализируйте "
                    + "аналитику и маркетинговые SDK только после подтверждения в баннере. 3. Очищайте "
                    + "хранилище при отказе от согласия.",
            "Трекинговые данные в localStorage до согласия не обнаружены",
            "Среди наблюдённых до согласия ключей localStorage трекинговых не зафиксировано.");

    /**
     * Reusable technical-правило: детектор jurisdiction-neutral, поэтому работает в слоях RU/EU/UK.
     * Per-layer legal-метаданные (RU: 152-ФЗ; EU: GDPR; UK: UK GDPR/PECR) резолвятся отдельно по
     * (code, layer); {@code definition()} даёт RU-метаданные own-слоя.
     */
    @Override
    public Set<JurisdictionLayer> supportedLayers() {
        return Set.of(JurisdictionLayer.RU, JurisdictionLayer.EU, JurisdictionLayer.UK);
    }

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public boolean appliesTo(ScanAnalysisContext ctx) {
        return CookieSupport.storageSnapshotAvailable(ctx);
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<PageAnalysisResult> pages = ctx.pages() == null ? List.of() : ctx.pages();
        List<RuleFact> facts = new ArrayList<>();
        for (PageAnalysisResult page : pages) {
            if (page.preConsentStorageKeys() == null || page.preConsentStorageKeys().isEmpty()) {
                continue;
            }
            Set<String> trackers = new LinkedHashSet<>();
            for (String key : page.preConsentStorageKeys()) {
                if (TrackerCookieNames.isTracker(key)) {
                    trackers.add(key);
                }
            }
            if (trackers.isEmpty()) {
                continue;
            }
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "На странице " + HttpHeaderSupport.shortUrl(page.url())
                            + " до согласия записаны трекинговые ключи localStorage: "
                            + String.join(", ", trackers) + ".",
                    page.url(),
                    SourceType.HTML,
                    EvidenceType.DYNAMIC_RENDER,
                    0.85,
                    "local-storage-tracking-before-consent;" + String.join(",", trackers),
                    VerificationStatus.DETECTED,
                    "LOCAL_STORAGE_TRACKING_BEFORE_CONSENT",
                    java.util.Map.of("page", HttpHeaderSupport.shortUrl(page.url()), "items", trackers)));
        }
        return facts;
    }
}
