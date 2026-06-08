package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleDefinition;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.RuleSupport;

import java.util.List;

/**
 * Не найдены реквизиты оператора (ИНН/ОГРН/ОГРНИП) в машиночитаемом тексте страниц. Метаданные
 * перенесены из MVP (okdocks {@code NO_OPERATOR_CONTACTS}). Детекция: {@link RuPatterns#OPERATOR_INFO_PATTERN}
 * по тексту страниц. В okdocks дополнительно парсился div-подвал через Jsoup; у нас текст подвала
 * уже входит в {@code text} страницы, отдельный Jsoup-парсинг не требуется.
 */
public final class NoOperatorContactsRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "NO_OPERATOR_CONTACTS",
            ScanJurisdiction.RU,
            FindingSeverity.MEDIUM,
            FindingCategory.DOCUMENTS,
            "Не найдены сведения об операторе персональных данных",
            "Зависит от квалификации: потенциально 30 000 – 60 000 ₽ для юрлиц по ч. 3 ст. 13.11 "
                    + "КоАП РФ либо 40 000 – 80 000 ₽ по ч. 4 ст. 13.11 КоАП РФ",
            "ст. 14, ст. 18.1 152-ФЗ; ст. 13.11 ч. 3 и ч. 4 КоАП РФ",
            "Сканер не обнаружил в машиночитаемом тексте страниц сведения, позволяющие "
                    + "идентифицировать оператора: наименование, адрес, ИНН, ОГРН или ОГРНИП. Само по себе "
                    + "отсутствие реквизитов не всегда означает нарушение, но недостаточная идентификация "
                    + "оператора повышает риск несоблюдения обязанностей по информированию субъектов ПДн. "
                    + "Если реквизиты размещены только в изображении или PDF, требуется ручная проверка.",
            "1. Разместите сведения об операторе в политике обработки ПДн. 2. Укажите наименование, "
                    + "юридический адрес, ИНН, ОГРН или ОГРНИП. 3. Добавьте контактный email для запросов "
                    + "субъектов ПДн. 4. Размещайте ключевые реквизиты в текстовом виде, а не только в "
                    + "изображении или PDF.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<PageAnalysisResult> pages = ctx.pages() == null ? List.of() : ctx.pages();
        if (pages.isEmpty() || RuPatterns.hasOperatorInfo(ctx)) {
            return List.of();
        }
        return List.of(new RuleFact(
                DEFINITION.code(),
                "Не найдены реквизиты оператора (ИНН/ОГРН/ОГРНИП) в тексте страниц. Возможно, "
                        + "реквизиты скрыты в изображении, PDF или загружаются динамически.",
                pages.get(0).url(),
                SourceType.HTML,
                RuleSupport.evidenceType(ctx),
                null,
                "operator-requisites-absent",
                VerificationStatus.UNVERIFIED));
    }
}
