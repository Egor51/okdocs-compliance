package io.okdocs.compliance.rules.common;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import io.okdocs.compliance.contracts.crawler.ScanAnalysisContext;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Нейтральные GDPR/UK-GDPR-эвристики: regex privacy notice, идентичности контролёра и прав субъекта
 * данных. Механика детекции одинакова для EU (GDPR) и UK (UK GDPR/DPA 2018) — английский + общие
 * европейские термины, поэтому helper живёт в {@code common} и переиспользуется пакетами {@code eu}
 * и {@code uk}. Отделён от RU-формулировок ({@code RuPatterns}). Детекция статическая (по тексту/
 * ссылкам страниц), без consent-сценариев.
 */
public final class GdprPatterns {

    private GdprPatterns() {
    }

    /** Ссылка/упоминание privacy notice (политики конфиденциальности). */
    public static final Pattern PRIVACY_NOTICE = Pattern.compile(
            "privacy[\\-_ ]?(polic|notice|statement)|/privacy\\b|data[\\-_ ]?protection"
                    + "|datenschutz" // de
                    + "|politique[\\-_ ]?de[\\-_ ]?confidentialit|confidentialit" // fr
                    + "|pol[íi]tica[\\-_ ]?de[\\-_ ]?privacidad|privacidad", // es
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Идентичность контролёра данных (controller identity): кто обрабатывает + контакт. */
    public static final Pattern CONTROLLER_IDENTITY = Pattern.compile(
            "data[\\-_ ]?controller|controller[\\-_ ]?of[\\-_ ]?(the[\\-_ ]?)?data"
                    + "|verantwortliche[r]?" // de
                    + "|responsable[\\-_ ]?du[\\-_ ]?traitement" // fr
                    + "|responsable[\\-_ ]?del[\\-_ ]?tratamiento" // es
                    + "|registered[\\-_ ]?(office|address)|company[\\-_ ]?(no|number|registration)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Права субъекта данных (access/erasure/rectification/portability/objection). */
    public static final Pattern DATA_SUBJECT_RIGHTS = Pattern.compile(
            "right[\\s\\S]{0,20}(to[\\s\\S]{0,5})?(access|erasure|be[\\s\\-]?forgotten|rectif"
                    + "|portabilit|object|restrict|withdraw)"
                    + "|data[\\-_ ]?subject[\\-_ ]?rights|your[\\-_ ]?(gdpr[\\-_ ]?)?rights"
                    + "|betroffenenrechte|auskunftsrecht" // de
                    + "|droit[\\s\\S]{0,20}(d['e]?acc[èe]s|effacement|rectification|opposition)" // fr
                    + "|derecho[\\s\\S]{0,20}(de[\\s\\S]{0,5})?(acceso|supresi[óo]n|rectificaci[óo]n|oposici[óo]n)", // es
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static List<PageAnalysisResult> pages(ScanAnalysisContext ctx) {
        return ctx.pages() == null ? List.of() : ctx.pages();
    }

    /** Есть ли где-либо privacy notice (внутренние ссылки или текст страницы). */
    public static boolean hasPrivacyNotice(ScanAnalysisContext ctx) {
        return matchesAnyPage(ctx, PRIVACY_NOTICE, true);
    }

    /** Раскрыта ли идентичность контролёра в тексте хотя бы одной страницы. */
    public static boolean hasControllerIdentity(ScanAnalysisContext ctx) {
        return matchesAnyPage(ctx, CONTROLLER_IDENTITY, false);
    }

    /** Упомянуты ли права субъекта данных в тексте хотя бы одной страницы. */
    public static boolean hasDataSubjectRights(ScanAnalysisContext ctx) {
        return matchesAnyPage(ctx, DATA_SUBJECT_RIGHTS, false);
    }

    private static boolean matchesAnyPage(ScanAnalysisContext ctx, Pattern pattern, boolean alsoLinks) {
        for (PageAnalysisResult p : pages(ctx)) {
            if (alsoLinks && p.internalLinks() != null) {
                for (String link : p.internalLinks()) {
                    if (pattern.matcher(link.toLowerCase(Locale.ROOT)).find()) {
                        return true;
                    }
                }
            }
            if (p.text() != null && pattern.matcher(p.text()).find()) {
                return true;
            }
        }
        return false;
    }
}
