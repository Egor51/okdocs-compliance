package io.okdocs.compliance.rules.common;

import io.okdocs.compliance.contracts.crawler.HttpResponseInfo;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.JurisdictionLayer;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mixed content: HTTPS-страница подгружает активные ресурсы (script/iframe/img/link и т.п.) по
 * {@code http://}. Браузер может заблокировать их или они передаются незашифрованно, ослабляя защиту
 * страницы с ПДн. Детекция по {@code src=}/{@code href=}-атрибутам с http:// в HTML страницы (Этап 1),
 * TLS-сокет не нужен. Считаем только страницы, которые сами отданы по HTTPS. Категория SECURITY,
 * основание — ст. 19 152-ФЗ + OWASP.
 */
public final class MixedContentRule implements Rule {

    /** src/href, указывающие на http:// (активные/пассивные подресурсы; навигационные <a> исключаем). */
    private static final Pattern HTTP_SUBRESOURCE = Pattern.compile(
            "(?:src|href)\\s*=\\s*[\"']http://([^\"'\\s>]+)", Pattern.CASE_INSENSITIVE);

    /** Навигационные ссылки <a href="http://..."> — не mixed content, отсеиваем по тегу <a. */
    private static final Pattern ANCHOR_HREF = Pattern.compile(
            "<a\\b[^>]*?href\\s*=\\s*[\"']http://", Pattern.CASE_INSENSITIVE);

    private static final RuleDefinition DEFINITION = new RuleDefinition(
            "MIXED_CONTENT_DETECTED",
            ScanJurisdiction.RU,
            FindingSeverity.MEDIUM,
            FindingCategory.SECURITY,
            "Смешанный контент: HTTPS-страница загружает ресурсы по HTTP",
            "Без прямого штрафа: организационно-техническая мера. Несоблюдение учитывается при "
                    + "оценке достаточности мер защиты ПДн по ст. 19 152-ФЗ.",
            "ст. 19 152-ФЗ (меры по обеспечению безопасности ПДн), OWASP Transport Layer Security",
            "Защищённая по HTTPS страница подключает скрипты, стили, изображения или фреймы по http://. "
                    + "Такие подресурсы передаются незашифрованно (и могут быть подменены), что ослабляет "
                    + "защиту страницы, в том числе формы сбора персональных данных.",
            "Замените все http://-ссылки на подресурсы на https:// (или протокол-относительные //). "
                    + "Используйте Content-Security-Policy с upgrade-insecure-requests для перехвата остатков.",
            "Смешанный контент не обнаружен",
            "На проверенных HTTPS-страницах не найдено подключение подресурсов по http://.");

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
    public List<RuleFact> evaluate(ScanAnalysisContext ctx) {
        Set<String> httpsPageUrls = httpsPageUrls(ctx);
        List<PageAnalysisResult> pages = ctx.pages() == null ? List.of() : ctx.pages();
        List<RuleFact> facts = new ArrayList<>();
        for (PageAnalysisResult page : pages) {
            // Mixed content определён только для страниц, отданных по HTTPS. URL страницы из html
            // (page.url) может быть https даже без technical-паспорта — проверяем оба источника.
            if (!isHttpsPage(page.url(), httpsPageUrls)) {
                continue;
            }
            String html = page.html();
            if (html == null || html.isBlank()) {
                continue;
            }
            Set<String> insecure = findInsecureSubresources(html);
            if (insecure.isEmpty()) {
                continue;
            }
            facts.add(new RuleFact(
                    DEFINITION.code(),
                    "HTTPS-страница " + HttpHeaderSupport.shortUrl(page.url())
                            + " подключает ресурсы по http://: " + String.join(", ", insecure) + ".",
                    page.url(),
                    SourceType.HTML,
                    RuleSupport.evidenceType(ctx),
                    0.90,
                    "mixed-content;" + String.join(";", insecure),
                    VerificationStatus.DETECTED));
        }
        return facts;
    }

    /** URL'ы из technical-паспорта, отданные по https (точная схема ответа). */
    private static Set<String> httpsPageUrls(ScanAnalysisContext ctx) {
        Set<String> urls = new LinkedHashSet<>();
        for (HttpResponseInfo r : RuleSupport.httpResponses(ctx)) {
            if (!r.redirect() && r.url() != null && r.url().toLowerCase(Locale.ROOT).startsWith("https://")) {
                urls.add(r.url());
            }
        }
        return urls;
    }

    private static boolean isHttpsPage(String pageUrl, Set<String> httpsPageUrls) {
        if (pageUrl == null) {
            return false;
        }
        return pageUrl.toLowerCase(Locale.ROOT).startsWith("https://") || httpsPageUrls.contains(pageUrl);
    }

    /** Хосты подресурсов, подключённых по http:// (исключая навигационные <a href>). */
    private static Set<String> findInsecureSubresources(String html) {
        Set<String> hosts = new LinkedHashSet<>();
        Matcher m = HTTP_SUBRESOURCE.matcher(html);
        while (m.find()) {
            String rest = m.group(1);
            // Отсеять навигационные <a href="http://...">: если ровно перед совпадением открыт тег <a.
            int matchStart = m.start();
            if (precededByAnchorTag(html, matchStart)) {
                continue;
            }
            int slash = rest.indexOf('/');
            String host = slash >= 0 ? rest.substring(0, slash) : rest;
            if (!host.isBlank()) {
                hosts.add(host.toLowerCase(Locale.ROOT));
            }
        }
        return hosts;
    }

    /** Грубая проверка: совпадение src/href относится к открытому тегу <a> (навигация, не подресурс). */
    private static boolean precededByAnchorTag(String html, int hrefPos) {
        int tagOpen = html.lastIndexOf('<', hrefPos);
        if (tagOpen < 0 || tagOpen + 2 > html.length()) {
            return false;
        }
        String tagStart = html.substring(tagOpen, Math.min(tagOpen + 3, html.length())).toLowerCase(Locale.ROOT);
        return tagStart.equals("<a ") || tagStart.equals("<a\t") || tagStart.startsWith("<a\n");
    }
}
