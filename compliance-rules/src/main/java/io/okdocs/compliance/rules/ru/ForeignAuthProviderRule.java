package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.RenderMode;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;
import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleDefinition;
import io.okdocs.compliance.rules.RuleFact;
import io.okdocs.compliance.rules.RuleSupport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Регистрация/авторизация через сторонний ИНОСТРАННЫЙ сервис (Sign in with Google / Apple /
 * Facebook / GitHub и т.п.). В отличие от пассивных трекеров ({@link ThirdPartyTrackersRule},
 * {@link CrossBorderTransferRule}), федеративный вход — активный канал: пользователь передаёт
 * идентификаторы и профильные ПДн иностранному оператору. Это образует риск трансграничной передачи
 * (ст. 12 152-ФЗ, уведомление РКН до начала передачи) и обработки третьим лицом (ст. 6, 18.1),
 * требующих раскрытия в политике и согласия.
 *
 * <p>Режимо-агностично (§3.2). Сигналы: домены OAuth-SDK ({@link RuForeignAuthProviders#SDK_DOMAINS})
 * в external script/style доменах + маркеры кнопок входа ({@link RuPatterns#hasForeignAuthMarker}).
 * Подтверждающий контекст — login/registration-форма рядом ({@link RuPatterns#hasLoginContext}),
 * отделяющий настоящий вход от share-виджета. На DYNAMIC — CONFIRMED/1.0; на STATIC при двух классах
 * сигналов — UNVERIFIED/0.85, при одном — UNVERIFIED/0.65 (вероятностно).
 */
public final class ForeignAuthProviderRule implements Rule {

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "POSSIBLE_FOREIGN_AUTH_PROVIDER",
            ScanJurisdiction.RU,
            FindingSeverity.HIGH,
            FindingCategory.FORMS,
            "Регистрация/авторизация через иностранный сервис без подтверждённых оснований",
            "Размер санкции зависит от установленного состава правонарушения и фактических "
                    + "обстоятельств; требуется отдельная юридическая квалификация",
            "ст. 12 152-ФЗ, ст. 6 152-ФЗ, ст. 18.1 152-ФЗ, ст. 13.11 КоАП РФ",
            "Вход или регистрация через сторонний иностранный сервис (Google, Apple, Facebook и др.) "
                    + "означает передачу идентификаторов и профильных персональных данных пользователя "
                    + "иностранному оператору. Если такая передача фактически происходит, до её начала "
                    + "оператор обязан направить в Роскомнадзор уведомление о трансграничной передаче, "
                    + "раскрыть обработку в политике и получить согласие. Сам факт наличия кнопки входа "
                    + "не всегда подтверждает передачу ПДн — требуется проверка фактических потоков данных.",
            "1. Определите, какие данные и каким иностранным сервисам передаются при входе/регистрации. "
                    + "2. Проверьте наличие уведомления о трансграничной передаче в РКН. 3. Раскройте "
                    + "использование сторонней авторизации в политике обработки ПДн и в согласии. "
                    + "4. Рассмотрите российские провайдеры входа (VK ID, Yandex ID, Сбер ID, Госуслуги) "
                    + "как альтернативу. 5. Не передавайте данные до получения согласия пользователя.",
            "Признаки иностранной OAuth/SSO-авторизации не обнаружены",
            "На обследованных страницах сканер не нашёл известные SDK-домены или маркеры кнопок иностранного OAuth/SSO. "
                    + "Серверные потоки и действия после входа не проверяются.");

    @Override
    public RuleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        List<PageAnalysisResult> pages = ctx.pages() == null ? List.of() : ctx.pages();
        EvidenceType evidenceType = RuleSupport.evidenceType(ctx);
        List<RuleFact> facts = new ArrayList<>();

        for (PageAnalysisResult p : pages) {
            // Считаем локально по сигналам именно этой страницы (P2), не «липкий» флаг на весь проход.
            Set<String> providers = new LinkedHashSet<>();
            matchProviders(p.externalScriptDomains(), providers);
            matchProviders(p.externalStyleDomains(), providers);
            boolean hasMarker = RuPatterns.hasForeignAuthMarker(p);
            if (providers.isEmpty() && !hasMarker) {
                continue;
            }

            boolean loginContext = RuPatterns.hasLoginContext(p);
            // Без login-контекста голый share-виджет иностранного сервиса — не «вход через него».
            // Его покрывает ThirdPartyTrackersRule/CrossBorderTransferRule, не это правило.
            if (!loginContext) {
                continue;
            }

            boolean strong = !providers.isEmpty() && hasMarker;
            VerificationStatus status;
            double confidence;
            if (p.renderMode() == RenderMode.DYNAMIC) {
                status = VerificationStatus.CONFIRMED;
                confidence = 1.0;
            } else {
                status = VerificationStatus.UNVERIFIED;
                confidence = strong ? 0.85 : 0.65;
            }

            String matched = providers.isEmpty() ? "auth-button" : String.join(",", providers);
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "Обнаружен вход/регистрация через сторонний иностранный сервис"
                            + (providers.isEmpty() ? "" : ": " + String.join(", ", providers))
                            + ". Проверьте уведомление о трансграничной передаче и раскрытие в политике.",
                    p.url(),
                    SourceType.HTML,
                    evidenceType,
                    confidence,
                    matched,
                    status));
        }
        return facts;
    }

    private static void matchProviders(List<String> domains, Set<String> found) {
        if (domains == null) {
            return;
        }
        for (String domain : domains) {
            for (String provider : RuForeignAuthProviders.SDK_DOMAINS) {
                if (RuleSupport.domainMatches(domain, provider)) {
                    found.add(provider);
                }
            }
        }
    }
}
