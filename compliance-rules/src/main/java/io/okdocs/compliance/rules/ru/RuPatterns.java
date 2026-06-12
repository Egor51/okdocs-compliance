package io.okdocs.compliance.rules.ru;

import io.okdocs.compliance.contracts.crawler.FormInfo;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.rules.RuleSupport;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RU-специфичные (152-ФЗ) эвристики для правил пакета {@code ru}: regex политики ПДн, реквизитов
 * оператора, cookie-баннера и согласия, плюс хелперы поверх них. Вынесено из jurisdiction-neutral
 * {@link RuleSupport}, чтобы будущие GDPR-правила (пакет {@code eu}) не подхватывали русские
 * формулировки. Перенос проверенной логики из MVP (okdocks {@code RuleSupport}).
 */
final class RuPatterns {

    private RuPatterns() {
    }

    /** Ссылка/упоминание политики обработки ПДн. */
    static final Pattern POLICY_LINK_PATTERN = Pattern.compile(
            "(политик[а-я]*\\s+(обработк|конф[еи]денциальн)|privacy[\\-_ ]?polic|/privacy\\b"
                    + "|personal[\\-_ ]?data|persdata|policy|конф[еи]денциальн|обработк[аи]\\s+перс|пд[нН])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Ключевые слова политики в тексте страницы (навигация/шапка). */
    static final Pattern POLICY_TEXT_HINT = Pattern.compile(
            "политик[аи]\\s+(конф[еи]денциальн|обработк|персональн)|политика\\s+пд"
                    + "|privacy\\s+policy|обработк[аи]\\s+персональных\\s+данных",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Реквизиты оператора (ИНН/ОГРН/КПП и т.п.) в любом типичном оформлении. */
    static final Pattern OPERATOR_INFO_PATTERN = Pattern.compile(
            "(ИНН|ОГРН|ОГРНИП|ОКПО|КПП)[\\s\\u00a0:№/\\-.,]{0,15}\\d[\\d\\s\\u00a0]{5,}",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Cookie-баннер: маркеры в html/тексте. */
    static final Pattern COOKIE_BANNER_PATTERN = Pattern.compile(
            "(cookie[\\-_ ]?consent|cookie[\\-_ ]?banner|cookie[\\-_ ]?notice|cookie[\\-_ ]?bar"
                    + "|cookie[\\-_ ]?message|cookie[\\-_ ]?policy|cookie[\\-_ ]?law|cookiebot|cookiehub"
                    + "|cookiepro|tarteaucitron|cc-window|gdpr|сайт\\s+использу[её]т\\s+cookie"
                    + "|мы\\s+использу[её]м\\s+cookie|файл[ыи]\\s+cookie|использовани[ея]\\s+файлов\\s+cookie"
                    + "|продолжа[яе]\\s+использовать.*cookie|cookie.*соглас|соглас.*cookie)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Inline-текст согласия рядом с кнопкой (без чекбокса). */
    static final Pattern INLINE_CONSENT_PATTERN = Pattern.compile(
            "(нажима[яе]|отправля[яе]|кликая).{0,60}(соглас|обработк|персональн)"
                    + "|(соглас|обработк).{0,60}(персональн|данн)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);

    /**
     * Контекст, в котором упоминание домена/бренда трекера в политике действительно похоже на
     * раскрытие аналитики/cookie, а не на футерную ссылку на карты, соцсети или навигацию.
     */
    private static final Pattern TRACKER_DISCLOSURE_CONTEXT = Pattern.compile(
            "(метрик|metric|analytics?|аналитик|статистик|cookie|куки|трекер|tracking|пиксел"
                    + "|pixel|маркетинг|реклам|обезлич|посещаемост)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern INN_PATTERN = Pattern.compile("(?<!\\d)(\\d{10}|\\d{12})(?!\\d)");

    /**
     * Маркеры кнопки/виджета федеративного входа через сторонний сервис: текстовые («Войти через
     * Google», «Continue with Apple») и структурные классы SDK-кнопок (Google Identity, Facebook
     * Login и др.). Вход для {@link ForeignAuthProviderRule} наряду со справочником
     * {@link RuForeignAuthProviders}.
     */
    static final Pattern FOREIGN_AUTH_MARKER = Pattern.compile(
            "((войти|вход|регистрац|sign[\\s\\-_]?in|sign[\\s\\-_]?up|log[\\s\\-_]?in|continue)"
                    + "[\\s\\S]{0,30}(google|apple|facebook|github|microsoft|discord))"
                    + "|g_id_signin|gsi-material-button|abcRioButton|data-onsuccess"
                    + "|fb-login-button|apple-signin|appleid-signin",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Маркеры login/registration-контекста на странице (текст рядом с механизмом входа). */
    private static final Pattern LOGIN_CONTEXT_PATTERN = Pattern.compile(
            "войти|вход\\b|авториз|регистрац|зарегистр|личн[ыа][йя]\\s+кабинет"
                    + "|sign[\\s\\-_]?in|sign[\\s\\-_]?up|log[\\s\\-_]?in|register",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ── Хелперы ──────────────────────────────────────────────────────────────────────────────

    private static List<PageAnalysisResult> pages(ScanAnalysisContext ctx) {
        return ctx.pages() == null ? List.of() : ctx.pages();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** Есть ли где-либо ссылка/упоминание политики ПДн (внутренние ссылки или текст страницы). */
    static boolean hasPolicyLink(ScanAnalysisContext ctx) {
        for (PageAnalysisResult p : pages(ctx)) {
            if (p.internalLinks() != null) {
                for (String link : p.internalLinks()) {
                    if (POLICY_LINK_PATTERN.matcher(link.toLowerCase(Locale.ROOT)).find()) {
                        return true;
                    }
                }
            }
            if (p.text() != null && POLICY_TEXT_HINT.matcher(p.text()).find()) {
                return true;
            }
        }
        return false;
    }

    /** Cookie-баннер: структурный флаг краулера ИЛИ RU-regex по html/тексту. */
    static boolean hasCookieBanner(ScanAnalysisContext ctx) {
        if (RuleSupport.hasCookieBannerFlag(ctx)) {
            return true;
        }
        for (PageAnalysisResult p : pages(ctx)) {
            String hay = (safe(p.html()) + " " + safe(p.text())).toLowerCase(Locale.ROOT);
            if (COOKIE_BANNER_PATTERN.matcher(hay).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Есть ли на странице механизм согласия: структурные флаги {@link FormInfo} ИЛИ inline-текст
     * согласия в тексте страницы (для JS-рендеренных форм, как в okdocks).
     */
    static boolean pageHasConsent(PageAnalysisResult page) {
        if (page.forms() != null) {
            for (FormInfo form : page.forms()) {
                if (form.hasConsentText() || (form.hasCheckbox() && consentByText(page))) {
                    return true;
                }
            }
        }
        return page.text() != null && INLINE_CONSENT_PATTERN.matcher(page.text()).find();
    }

    private static boolean consentByText(PageAnalysisResult page) {
        String t = safe(page.text()).toLowerCase(Locale.ROOT);
        return t.contains("соглас") || t.contains("персональн") || t.contains("обработк")
                || t.contains("consent") || t.contains("конфиденциальн");
    }

    /** Первый ИНН (10 или 12 цифр) в тексте любой страницы — идентификация оператора. */
    static Optional<String> parseInn(ScanAnalysisContext ctx) {
        for (PageAnalysisResult page : pages(ctx)) {
            if (page.text() == null) {
                continue;
            }
            Matcher m = INN_PATTERN.matcher(page.text());
            if (m.find()) {
                return Optional.of(m.group(1));
            }
        }
        return Optional.empty();
    }

    /** Реквизиты оператора найдены в тексте хотя бы одной страницы. */
    static boolean hasOperatorInfo(ScanAnalysisContext ctx) {
        for (PageAnalysisResult p : pages(ctx)) {
            if (p.text() != null && OPERATOR_INFO_PATTERN.matcher(p.text()).find()) {
                return true;
            }
        }
        return false;
    }

    /** Упоминаются ли переданные домены трекеров в тексте страниц, похожих на политику ПДн. */
    static boolean trackersMentionedInPolicy(ScanAnalysisContext ctx, Set<String> domains) {
        String policyText = pages(ctx).stream()
                .filter(RuPatterns::isPolicyPage)
                .map(p -> safe(p.text()))
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
        if (policyText.isBlank()) {
            return false;
        }
        for (String domain : domains) {
            if (trackerMentionedWithDisclosureContext(policyText, domain)) {
                return true;
            }
        }
        return false;
    }

    private static boolean trackerMentionedWithDisclosureContext(String policyText, String domain) {
        for (String alias : trackerAliases(domain)) {
            int from = 0;
            while (from < policyText.length()) {
                int idx = policyText.indexOf(alias, from);
                if (idx < 0) {
                    break;
                }
                int start = Math.max(0, idx - 140);
                int end = Math.min(policyText.length(), idx + alias.length() + 140);
                String window = policyText.substring(start, end);
                if (TRACKER_DISCLOSURE_CONTEXT.matcher(window).find()) {
                    return true;
                }
                from = idx + alias.length();
            }
        }
        return false;
    }

    private static boolean isPolicyPage(PageAnalysisResult p) {
        String url = safe(p.url()).toLowerCase(Locale.ROOT);
        if (url.contains("privac") || url.contains("policy") || url.contains("политик")
                || url.contains("personal") || url.contains("consent")) {
            return true;
        }
        return POLICY_TEXT_HINT.matcher(safe(p.text())).find();
    }

    private static Set<String> trackerAliases(String domain) {
        String simple = trackerSimpleName(domain);
        return switch (simple) {
            case "yandex" -> Set.of("yandex", "яндекс");
            case "google" -> Set.of("google", "google analytics", "гугл");
            case "facebook" -> Set.of("facebook", "meta", "фейсбук");
            case "mail" -> Set.of("mail.ru", "mail", "мэйл");
            default -> Set.of(simple);
        };
    }

    // "google-analytics.com" → "google", "mc.yandex.ru" → "yandex", "connect.facebook.net" → "facebook"
    private static String trackerSimpleName(String domain) {
        String[] parts = domain.split("\\.");
        String first = parts[0].toLowerCase(Locale.ROOT);
        return switch (first) {
            case "mc", "top", "connect" -> parts.length > 1 ? parts[1] : first;
            case "google-analytics" -> "google";
            default -> first;
        };
    }

    // ── Федеративный вход (ForeignAuthProviderRule) ─────────────────────────────────────────────

    /**
     * На странице есть маркер кнопки/виджета федеративного входа через сторонний сервис
     * (текст «Войти через Google» или структурный класс SDK-кнопки) — ищем в html и тексте.
     */
    static boolean hasForeignAuthMarker(PageAnalysisResult page) {
        String hay = safe(page.html()) + " " + safe(page.text());
        return FOREIGN_AUTH_MARKER.matcher(hay).find();
    }

    /**
     * Страница похожа на login/registration: есть password-поле в форме ИЛИ текстовые маркеры входа
     * («войти», «регистрация», «sign in»). Подтверждающий контекст, отделяющий настоящий вход через
     * сторонний сервис от голого share-виджета.
     */
    static boolean hasLoginContext(PageAnalysisResult page) {
        if (page.forms() != null) {
            for (FormInfo form : page.forms()) {
                if (form.hasPasswordField()) {
                    return true;
                }
            }
        }
        String hay = safe(page.title()) + " " + safe(page.text());
        return LOGIN_CONTEXT_PATTERN.matcher(hay).find();
    }
}
