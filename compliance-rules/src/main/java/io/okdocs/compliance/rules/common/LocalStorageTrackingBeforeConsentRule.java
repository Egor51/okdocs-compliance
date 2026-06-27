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
 * {@link TrackerCookieNames}. Категория COOKIES, основание — ст. 6, ст. 9 152-ФЗ + ст. 13.11 КоАП РФ.
 */
public final class LocalStorageTrackingBeforeConsentRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "LOCAL_STORAGE_TRACKING_BEFORE_CONSENT",
            ScanJurisdiction.RU,
            FindingSeverity.MEDIUM,
            FindingCategory.COOKIES,
            "Трекинговые данные в localStorage до получения согласия",
            "150 000 – 300 000 ₽ для юрлиц; при повторном нарушении 300 000 – 500 000 ₽ для юрлиц",
            "ст. 13.11 ч. 1 и ч. 1.1 КоАП РФ, ст. 6, ст. 9 152-ФЗ",
            "В Web Storage (localStorage) до взаимодействия с cookie-баннером уже присутствуют ключи "
                    + "аналитических/маркетинговых сервисов. Хранение трекинговых идентификаторов в "
                    + "localStorage — способ обойти cookie-баннер; их запись до согласия так же нарушает "
                    + "требование о согласии до начала обработки ПДн, как и cookie.",
            "1. Не записывайте трекинговые идентификаторы в localStorage до согласия. 2. Инициализируйте "
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
                    VerificationStatus.CONFIRMED));
        }
        return facts;
    }
}
