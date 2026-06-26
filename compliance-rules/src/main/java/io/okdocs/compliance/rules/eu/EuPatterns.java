package io.okdocs.compliance.rules.eu;

import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.rules.common.GdprPatterns;

/**
 * GDPR-эвристики пакета {@code eu}. Тонкая обёртка над нейтральным {@link GdprPatterns} (общий с
 * {@code uk}, т.к. механика детекции privacy notice / controller / rights одинакова для GDPR и
 * UK GDPR). Оставлена как точка для EU-специфичных эвристик, если появятся.
 */
final class EuPatterns {

    private EuPatterns() {
    }

    static boolean hasPrivacyNotice(ScanAnalysisContext ctx) {
        return GdprPatterns.hasPrivacyNotice(ctx);
    }

    static boolean hasControllerIdentity(ScanAnalysisContext ctx) {
        return GdprPatterns.hasControllerIdentity(ctx);
    }

    static boolean hasDataSubjectRights(ScanAnalysisContext ctx) {
        return GdprPatterns.hasDataSubjectRights(ctx);
    }
}
