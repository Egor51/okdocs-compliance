package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.contracts.scan.SanctionExposureDto;
import io.okdocs.compliance.contracts.scan.SanctionScenarioDto;
import io.okdocs.compliance.persistence.scan.ComplianceFinding;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Версионируемый RU-каталог санкционных сценариев для внешне наблюдаемых risks. Значения не
 * извлекаются из free-text metadata. Каталог намеренно консервативен: технические меры без прямой
 * санкции сюда не попадают, а условные сценарии сопровождаются applicability.
 */
final class RuSanctionCatalog {

    static final String SOURCE_URL =
            "https://www.consultant.ru/document/cons_doc_LAW_34661/"
                    + "1f421640c6775ff67079ebde06a7d2f6d17b96db/";
    private static final LocalDate VERIFIED_ON = LocalDate.of(2026, 7, 13);
    private static final String RUB = "RUB";

    private static final Set<String> GENERAL_PROCESSING_CODES = Set.of(
            "UNPROTECTED_DATA_FORMS",
            "CONSENT_DEFAULT_CHECKED",
            "THIRD_PARTY_TRACKERS",
            "POSSIBLE_TRACKERS_BEFORE_CONSENT",
            "TRACKING_COOKIES_BEFORE_CONSENT",
            "LOCAL_STORAGE_TRACKING_BEFORE_CONSENT",
            "RU_CONSENT_CHOICE_NOT_EFFECTIVE");

    private RuSanctionCatalog() {
    }

    static SanctionExposureDto exposure(List<ComplianceFinding> findings) {
        Set<String> activeCodes = observedCodes(findings);
        if (activeCodes.isEmpty()) {
            return null;
        }

        List<SanctionScenarioDto> scenarios = new ArrayList<>();
        List<String> generalCodes = intersection(activeCodes, GENERAL_PROCESSING_CODES);
        if (!generalCodes.isEmpty()) {
            scenarios.add(scenario(
                    "KOAP_13_11_1_LEGAL_FIRST",
                    "обработка без предусмотренного законом основания",
                    generalCodes, "13.11", "1", "Юридическое лицо", "FIRST",
                    150_000, 300_000,
                    "Только если проверка подтвердит обработку ПДн без применимого основания или "
                            + "несовместимость обработки с заявленной целью."));
            scenarios.add(scenario(
                    "KOAP_13_11_1_1_LEGAL_REPEAT",
                    "повторная обработка без предусмотренного законом основания",
                    generalCodes, "13.11", "1.1", "Юридическое лицо или ИП", "REPEATED",
                    300_000, 500_000,
                    "Только при подтверждении состава части 1 и юридически установленной повторности."));
        }

        if (activeCodes.contains("NO_PRIVACY_POLICY")) {
            scenarios.add(scenario(
                    "KOAP_13_11_3_IP",
                    "неопубликованная политика обработки ПДн для ИП",
                    List.of("NO_PRIVACY_POLICY"), "13.11", "3", "Индивидуальный предприниматель", "FIRST",
                    10_000, 20_000,
                    "Применимо, если обязанность публикации существует, а доступ к политике действительно не обеспечен."));
            scenarios.add(scenario(
                    "KOAP_13_11_3_LEGAL",
                    "неопубликованная политика обработки ПДн для организации",
                    List.of("NO_PRIVACY_POLICY"), "13.11", "3", "Юридическое лицо", "FIRST",
                    30_000, 60_000,
                    "Применимо, если обязанность публикации существует, а доступ к политике действительно не обеспечен."));
        }

        if (activeCodes.contains("RKN_REGISTRY_NOT_VERIFIED")) {
            scenarios.add(scenario(
                    "KOAP_13_11_10_NOTICE",
                    "непредставление или несвоевременное представление уведомления Роскомнадзору",
                    List.of("RKN_REGISTRY_NOT_VERIFIED"), "13.11", "10", "Юридическое лицо или ИП", "FIRST",
                    100_000, 300_000,
                    "Только если официальный реестр и документы подтвердят обязанность уведомления и её невыполнение."
            ));
        }

        if (activeCodes.contains("HOSTING_OUTSIDE_RU_DETECTED")) {
            scenarios.add(scenario(
                    "KOAP_13_11_8_LOCALIZATION_FIRST",
                    "первое нарушение требования локализации баз данных",
                    List.of("HOSTING_OUTSIDE_RU_DETECTED"), "13.11", "8", "Юридическое лицо или ИП", "FIRST",
                    1_000_000, 6_000_000,
                    "Публичный IP сайта не доказывает нарушение. Сценарий применим только если ручная "
                            + "проверка подтвердит операции с ПДн граждан РФ в зарубежной базе данных."));
            scenarios.add(scenario(
                    "KOAP_13_11_9_LOCALIZATION_REPEAT",
                    "повторное нарушение требования локализации баз данных",
                    List.of("HOSTING_OUTSIDE_RU_DETECTED"), "13.11", "9", "Юридическое лицо или ИП", "REPEATED",
                    6_000_000, 18_000_000,
                    "Публичный IP сайта не доказывает нарушение. Дополнительно требуется юридически "
                            + "установленная повторность нарушения требования локализации."));
        }

        if (scenarios.isEmpty()) {
            return null;
        }
        scenarios.sort(Comparator.comparingLong(SanctionScenarioDto::maximumAmount).reversed()
                .thenComparing(SanctionScenarioDto::id));
        SanctionScenarioDto maximum = scenarios.get(0);
        return new SanctionExposureDto(
                "До " + formatRubles(maximum.maximumAmount()) + " ₽ — " + maximum.label(),
                maximum.maximumAmount(),
                RUB,
                "MAX_RELEVANT_SCENARIO",
                true,
                true,
                scenarios);
    }

    private static Set<String> observedCodes(List<ComplianceFinding> findings) {
        Set<String> codes = new LinkedHashSet<>();
        if (findings == null) {
            return codes;
        }
        for (ComplianceFinding finding : findings) {
            if (finding == null || finding.getCode() == null) {
                continue;
            }
            VerificationStatus status = finding.getVerificationStatus();
            if (status == VerificationStatus.CONFIRMED || status == VerificationStatus.DETECTED) {
                codes.add(finding.getCode());
            }
        }
        return codes;
    }

    private static List<String> intersection(Set<String> active, Set<String> supported) {
        return active.stream().filter(supported::contains).sorted().toList();
    }

    private static SanctionScenarioDto scenario(
            String id, String label, List<String> relatedCodes, String article, String part,
            String subjectType, String recurrence, long min, long max, String applicability) {
        return new SanctionScenarioDto(id, label, relatedCodes, "КоАП РФ", article, part,
                subjectType, recurrence, min, max, RUB, applicability, SOURCE_URL, VERIFIED_ON);
    }

    private static String formatRubles(long amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0", symbols).format(amount);
    }
}
