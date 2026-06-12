package io.okdocs.compliance.worker.crawler;

import io.okdocs.compliance.contracts.crawler.FormInfo;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.enums.RenderMode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Проекция Jsoup {@link Document} → контрактный {@link PageAnalysisResult}/{@link FormInfo}
 * (вход движка правил, §1.6). Старый okdocks {@code PageAnalysisResult} был DOM-ориентирован
 * (нёс {@code List<Element>}); новый контракт — нормализованный набор готовых флагов, поэтому
 * extraction-слой написан заново.
 * <p>
 * Семантика «какое имя поля = ПДн», «есть ли согласие», «есть ли ссылка на политику в форме» —
 * юрисдикционно-зависимая (RU/152-ФЗ) и живёт здесь, в краулере, а НЕ в jurisdiction-neutral
 * движке правил: правила читают готовые булевы флаги {@link FormInfo}.
 */
final class PageExtractor {

    private PageExtractor() {
    }

    // Извлекает домены из текста inline-скриптов (e.g. "mc.yandex.ru/metrika/tag.js")
    private static final Pattern INLINE_SCRIPT_DOMAIN = Pattern.compile(
            "(?:https?:)?//([a-zA-Z0-9][a-zA-Z0-9\\-.]{1,253}[a-zA-Z0-9])/");

    // ПДн-поля формы (имя/id/placeholder/type). Технические поля (hidden/submit/...) отфильтрованы.
    private static final Pattern PD_FIELD_PATTERN = Pattern.compile(
            "(email|e-mail|почта|mail|phone|tel|телефон|name|имя|фамил|surname|fio|фио"
                    + "|address|адрес|passport|паспорт|inn|инн)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // Текст согласия на обработку ПДн рядом с чекбоксом/кнопкой.
    private static final Pattern CONSENT_TEXT_PATTERN = Pattern.compile(
            "(соглас|персональн|обработк|consent|конфиденциальн)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // Ссылка на политику ПДн внутри формы (href или текст ссылки).
    private static final Pattern POLICY_LINK_PATTERN = Pattern.compile(
            "(privacy|polic|политик|конфиденц|персональн|обработк|personal[\\-_ ]?data|persdata|пдн)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // Маркеры cookie-баннера в html (структурный сигнал; RU-regex по тексту — в RuPatterns).
    private static final Pattern COOKIE_FLAG_PATTERN = Pattern.compile(
            "(cookie[\\-_ ]?consent|cookie[\\-_ ]?banner|cookie[\\-_ ]?notice|cookie[\\-_ ]?bar"
                    + "|cookiebot|cookiehub|cookiepro|cc-window|использу[её]т\\s+cookie"
                    + "|файл[ыи]\\s+cookie)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** STATIC-извлечение (Jsoup-краул). */
    static PageAnalysisResult extract(String url, Document doc, String startDomain) {
        return extract(url, doc, startDomain, RenderMode.STATIC);
    }

    /** Извлечение с явным {@link RenderMode}: STATIC для Jsoup, DYNAMIC для headless-рендера (CDP). */
    static PageAnalysisResult extract(String url, Document doc, String startDomain, RenderMode renderMode) {
        return extract(url, doc, startDomain, renderMode, List.of());
    }

    /**
     * Извлечение с таймлайном «трекер до согласия»: {@code preConsentTrackerHosts} — сторонние хосты,
     * чьи запросы наблюдались до cookie-баннера (DYNAMIC через CDP). На STATIC всегда пусто.
     */
    static PageAnalysisResult extract(String url, Document doc, String startDomain, RenderMode renderMode,
                                      List<String> preConsentTrackerHosts) {
        String html = doc.html();
        String text = doc.text();

        Set<String> internalLinks = new LinkedHashSet<>();
        Set<String> scriptDomains = new LinkedHashSet<>();
        Set<String> styleDomains = new LinkedHashSet<>();

        for (Element a : doc.select("a[href]")) {
            String href = a.absUrl("href");
            if (href.isBlank()) {
                continue;
            }
            String domain = extractDomain(href);
            if (domain != null && isSameDomainOrSubdomain(domain, startDomain)) {
                internalLinks.add(href);
            }
        }
        for (Element s : doc.select("script[src]")) {
            String domain = extractDomain(s.absUrl("src"));
            if (domain != null && !isSameDomainOrSubdomain(domain, startDomain)) {
                scriptDomains.add(domain.toLowerCase(Locale.ROOT));
            }
        }
        for (Element s : doc.select("script:not([src])")) {
            String data = s.data();
            if (data == null || data.isBlank()) {
                continue;
            }
            var m = INLINE_SCRIPT_DOMAIN.matcher(data);
            while (m.find()) {
                String domain = m.group(1).toLowerCase(Locale.ROOT);
                if (isSameDomainOrSubdomain(domain, startDomain)
                        || domain.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                    continue;
                }
                scriptDomains.add(domain);
            }
        }
        for (Element l : doc.select("link[href]")) {
            String domain = extractDomain(l.absUrl("href"));
            if (domain != null && !isSameDomainOrSubdomain(domain, startDomain)) {
                styleDomains.add(domain.toLowerCase(Locale.ROOT));
            }
        }

        List<FormInfo> forms = new ArrayList<>();
        for (Element form : doc.select("form")) {
            forms.add(extractForm(form));
        }

        boolean cookiePresent = COOKIE_FLAG_PATTERN.matcher(html).find();

        return new PageAnalysisResult(
                url,
                doc.title(),
                text,
                html,
                List.copyOf(scriptDomains),
                List.copyOf(styleDomains),
                List.copyOf(internalLinks),
                cookiePresent,
                List.copyOf(forms),
                renderMode,
                preConsentTrackerHosts == null ? List.of() : List.copyOf(preConsentTrackerHosts));
    }

    private static FormInfo extractForm(Element form) {
        String action = form.absUrl("action");
        if (action.isBlank()) {
            action = form.attr("action");
        }
        String method = form.attr("method").isBlank() ? "get" : form.attr("method").toLowerCase(Locale.ROOT);

        List<String> inputNames = new ArrayList<>();
        boolean hasPassword = false;
        boolean hasFileUpload = false;
        boolean hasCheckbox = false;
        boolean hasPdField = false;
        boolean hasDefaultCheckedConsent = false;

        // Чекбокс согласия может быть снаружи <form> — расширяем поиск на родительский контейнер.
        Element scope = form.parent() != null ? form.parent() : form;
        String scopeText = scope.text();

        for (Element input : form.select("input, textarea, select")) {
            String type = input.attr("type").toLowerCase(Locale.ROOT);
            String name = input.attr("name");
            if (!name.isBlank()) {
                inputNames.add(name);
            }
            switch (type) {
                case "hidden", "submit", "button", "image", "reset", "search" -> {
                    continue;
                }
                case "password" -> hasPassword = true;
                case "file" -> hasFileUpload = true;
                default -> { /* fall through to PD-field check */ }
            }
            String haystack = (name + " " + input.attr("id") + " "
                    + input.attr("placeholder") + " " + type).toLowerCase(Locale.ROOT);
            if (PD_FIELD_PATTERN.matcher(haystack).find()) {
                hasPdField = true;
            }
        }

        for (Element box : scope.select("input[type=checkbox]")) {
            hasCheckbox = true;
            String boxCtxText = box.parent() != null ? box.parent().text() : scopeText;
            String boxHay = (box.attr("name") + " " + box.attr("id") + " "
                    + box.attr("value") + " " + boxCtxText).toLowerCase(Locale.ROOT);
            if (CONSENT_TEXT_PATTERN.matcher(boxHay).find() && box.hasAttr("checked")) {
                hasDefaultCheckedConsent = true;
            }
        }

        boolean hasConsentText = CONSENT_TEXT_PATTERN.matcher(scopeText).find();

        boolean hasPrivacyPolicyLink = false;
        for (Element a : scope.select("a[href]")) {
            String hay = (a.attr("href") + " " + a.text()).toLowerCase(Locale.ROOT);
            if (POLICY_LINK_PATTERN.matcher(hay).find()) {
                hasPrivacyPolicyLink = true;
                break;
            }
        }

        return new FormInfo(
                action,
                method,
                List.copyOf(inputNames),
                hasPassword,
                hasFileUpload,
                hasCheckbox,
                hasConsentText,
                hasPrivacyPolicyLink,
                hasDefaultCheckedConsent,
                hasPdField);
    }

    static String extractDomain(String url) {
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    static boolean isSameDomainOrSubdomain(String host, String rootDomain) {
        if (host == null || rootDomain == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        String d = rootDomain.toLowerCase(Locale.ROOT);
        return h.equals(d) || h.endsWith("." + d);
    }
}
