package io.okdocs.compliance.rules;

import io.okdocs.compliance.contracts.crawler.DnsInfo;
import io.okdocs.compliance.contracts.crawler.FormInfo;
import io.okdocs.compliance.contracts.crawler.HttpResponseInfo;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;
import io.okdocs.compliance.contracts.crawler.TechnicalAnalysisResult;
import io.okdocs.compliance.contracts.crawler.TlsInfo;
import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.RenderMode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Jurisdiction-neutral утилиты для правил: только то, что не зависит от конкретного закона
 * (152-ФЗ / GDPR). Юрисдикционно-зависимые эвристики (regex политики/реквизитов, справочники
 * трекеров) живут в пакетах правил соответствующей юрисдикции (напр. {@code ru}), чтобы будущие
 * GDPR-правила не подхватывали RU-эвристики случайно.
 */
public final class RuleSupport {

    private RuleSupport() {
    }

    private static List<PageAnalysisResult> pages(ScanAnalysisContext ctx) {
        return ctx.pages() == null ? List.of() : ctx.pages();
    }

    /** Собирает ли форма ПДн — читает готовый флаг краулера (семантика полей живёт в краулере). */
    public static boolean collectsData(FormInfo form) {
        return form.hasPdField() || form.hasPasswordField() || form.hasFileUpload();
    }

    /** Есть ли на сайте форма, собирающая персональные данные. */
    public static boolean hasDataForms(ScanAnalysisContext ctx) {
        return pages(ctx).stream()
                .filter(p -> p.forms() != null)
                .flatMap(p -> p.forms().stream())
                .anyMatch(RuleSupport::collectsData);
    }

    /** Cookie-баннер обнаружен краулером хотя бы на одной странице (структурный флаг, neutral). */
    public static boolean hasCookieBannerFlag(ScanAnalysisContext ctx) {
        return pages(ctx).stream().anyMatch(PageAnalysisResult::cookiePresent);
    }

    /** Уникальные домены внешних скриптов со всех страниц (порядок обхода сохранён). */
    public static Set<String> externalScripts(ScanAnalysisContext ctx) {
        Set<String> domains = new LinkedHashSet<>();
        for (PageAnalysisResult page : pages(ctx)) {
            if (page.externalScriptDomains() != null) {
                domains.addAll(page.externalScriptDomains());
            }
        }
        return domains;
    }

    /** Совпадает ли домен с эталонным (точное равенство или поддомен). */
    public static boolean domainMatches(String domain, String reference) {
        return domain.equals(reference) || domain.endsWith("." + reference);
    }

    /**
     * HTTP-ответы из технического паспорта скана (headers/redirect-цепочка). Пусто, если
     * technical-анализ не проводился ({@code ctx.technical() == null}) — technical-правила тогда
     * не создают находок. Централизует null-проверку, чтобы 9 security-header правил её не дублировали.
     */
    public static List<HttpResponseInfo> httpResponses(ScanAnalysisContext ctx) {
        TechnicalAnalysisResult technical = ctx.technical();
        if (technical == null || technical.responses() == null) {
            return List.of();
        }
        return technical.responses();
    }

    /**
     * DNS-обогащение из технического паспорта или {@code null}, если technical-анализ не проводился
     * ({@code ctx.technical() == null}). DNS-правила при {@code null} не создают находок.
     */
    public static DnsInfo dnsInfo(ScanAnalysisContext ctx) {
        TechnicalAnalysisResult technical = ctx.technical();
        return technical == null ? null : technical.dns();
    }

    /**
     * Результаты TLS-осмотра из технического паспорта. Пусто, если technical-анализ не проводился
     * ({@code ctx.technical() == null}) — TLS-правила тогда не создают находок. Централизует
     * null-проверку, чтобы TLS-правила её не дублировали.
     */
    public static List<TlsInfo> tlsInfos(ScanAnalysisContext ctx) {
        TechnicalAnalysisResult technical = ctx.technical();
        if (technical == null || technical.tls() == null) {
            return List.of();
        }
        return technical.tls();
    }

    /**
     * {@code DYNAMIC_RENDER}, если хотя бы одна страница получена в DYNAMIC-режиме, иначе
     * {@code STATIC_ANALYSIS}. На STATIC-сканах (free) — всегда STATIC_ANALYSIS.
     */
    public static EvidenceType evidenceType(ScanAnalysisContext ctx) {
        boolean anyDynamic = pages(ctx).stream().anyMatch(p -> p.renderMode() == RenderMode.DYNAMIC);
        return anyDynamic ? EvidenceType.DYNAMIC_RENDER : EvidenceType.STATIC_ANALYSIS;
    }
}
