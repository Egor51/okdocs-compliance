package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleDefinition;
import io.okdocs.compliance.rules.RuleFact;

import java.util.List;
import java.util.Locale;

/**
 * Сервер сайта расположен за пределами РФ (риск нарушения локализации, ч. 5 ст. 18 152-ФЗ).
 * Страна хостинга уже выложена worker'ом в {@link ScanAnalysisContext#hostCountry()} из DNS/GeoIP
 * enrichment — правило остаётся чистой функцией.
 * <p>
 * GeoIP мог не разрезолвить IP: {@code hostCountry == null} → UNVERIFIED (не молчим, PLAN.md §1.6).
 * Метаданные перенесены из MVP (okdocks {@code NON_RU_HOSTING}). confidence для подтверждённого
 * зарубежного хостинга = 0.85.
 */
public final class NonRuHostingRule implements Rule {

    private static final String RU = "RU";

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "HOSTING_OUTSIDE_RU_DETECTED",
            ScanJurisdiction.RU,
            FindingSeverity.HIGH,
            FindingCategory.HOSTING,
            "Сервер сайта находится за пределами Российской Федерации",
            "1 000 000 – 6 000 000 ₽ для юрлиц при первичном нарушении; при повторном нарушении "
                    + "до 18 000 000 ₽",
            "ч. 5 ст. 18 152-ФЗ (локализация ПДн граждан РФ), ст. 13.11 ч. 8 КоАП РФ",
            "Оператор обязан обеспечить хранение и обработку персональных данных граждан РФ с "
                    + "использованием баз данных, расположенных на территории РФ. IP-адрес сервера сайта "
                    + "определён как зарубежный. Если на сайте собираются ПДн граждан РФ, это создаёт высокий "
                    + "риск нарушения требования локализации. Для окончательной квалификации требуется "
                    + "проверка фактического расположения базы данных и инфраструктуры.",
            "1. Переместите хранение ПДн граждан РФ на серверы, физически расположенные в России. "
                    + "2. Если используется зарубежный хостинг — убедитесь, что база данных с ПДн вынесена "
                    + "на российскую площадку. 3. Проверьте договоры с облачными провайдерами. 4. При "
                    + "использовании CDN убедитесь, что первичное хранение данных происходит в РФ.",
            "Публичный IP основного домена GeoIP-база отнесла к РФ",
            "GeoIP-проверка публичного IP основного домена вернула RU. Это не подтверждает местонахождение "
                    + "баз данных или выполнение требования локализации.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        String country = ctx.hostCountry();

        if (country == null || country.isBlank()) {
            // GeoIP не разрезолвил — честно говорим «не удалось проверить», а не молчим.
            return List.of(new RuleFact(
                    DEFINITION.code(),
                    "Не удалось определить страну хостинга сервера (GeoIP недоступен). "
                            + "Требуется ручная проверка локализации ПДн.",
                    null,
                    SourceType.HTML,
                    EvidenceType.STATIC_ANALYSIS,
                    null,
                    "host-country-unknown",
                    VerificationStatus.UNVERIFIED));
        }

        if (RU.equalsIgnoreCase(country)) {
            return List.of();
        }

        return List.of(new RuleFact(
                DEFINITION.code(),
                "Сервер сайта расположен в: " + country.toUpperCase(Locale.ROOT) + ".",
                null,
                SourceType.HTML,
                EvidenceType.STATIC_ANALYSIS,
                0.85,
                "host-country=" + country.toUpperCase(Locale.ROOT),
                VerificationStatus.DETECTED));
    }
}
