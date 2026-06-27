package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.rules.RuleFact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Рендерит локализуемый текст evidence из {@link RuleFact#evidenceKey()} + {@link RuleFact#params()}
 * по locale пользователя (§ PLAN-evidence-localization, Этап 2). Шаблоны — resource-bundles
 * {@code evidence_<locale>.properties} в worker (НЕ в {@code compliance-rules}, который остаётся
 * neutral). Подстановка {@code {param}} из params (списки → "a, b, c").
 * <p>
 * <b>Контракт fallback:</b>
 * <ol>
 *   <li>{@code evidenceKey == null} → вернуть legacy {@code fact.evidence()} (plain).</li>
 *   <li>есть key, но нет шаблона/bundle для locale → fallback на {@code en}, затем на plain.</li>
 *   <li>нет ни en-шаблона, ни plain → вернуть {@code null} (находка без evidence).</li>
 * </ol>
 * На Этапе 2 ни один детектор ещё не задаёт {@code evidenceKey} → всегда ветка (1): поведение не
 * меняется. Локализация включается по мере миграции детекторов (Этап 3).
 */
@Slf4j
@Component
public class EvidenceRenderer {

    private static final String BUNDLE = "evidence";
    private static final String FALLBACK_LANG = "en";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    /**
     * NO-FALLBACK control: {@link ResourceBundle#getBundle} иначе при отсутствии bundle для locale
     * откатывается на bundle ДЕФОЛТНОГО locale JVM (напр. ru), ломая нашу явную цепочку locale→en→plain.
     * Этот control запрещает default-fallback — отсутствие точного языка даёт {@link MissingResourceException}.
     */
    private static final ResourceBundle.Control NO_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private static final String MESSAGES_BUNDLE = "messages";

    /** Текст evidence для finding под заданный locale. */
    public String render(RuleFact fact, String locale) {
        if (fact == null) {
            return null;
        }
        String key = fact.evidenceKey();
        if (key == null || key.isBlank()) {
            return fact.evidence(); // (1) legacy plain
        }
        String template = lookupTemplate(BUNDLE, key, locale);
        if (template == null) {
            // (2)/(3): нет шаблона ни для locale, ни для en → plain как последний fallback.
            return fact.evidence();
        }
        return substitute(template, fact.params());
    }

    /**
     * Текст {@code RuleOutcome.message} под locale (§ Этап 4). Тот же контракт fallback, что у
     * evidence: {@code messageKey==null} → plain {@code fallbackMessage}; нет шаблона → en → plain.
     */
    public String renderMessage(String messageKey, Map<String, Object> params, String fallbackMessage,
                                String locale) {
        if (messageKey == null || messageKey.isBlank()) {
            return fallbackMessage;
        }
        String template = lookupTemplate(MESSAGES_BUNDLE, messageKey, locale);
        if (template == null) {
            return fallbackMessage;
        }
        return substitute(template, params);
    }

    /** Шаблон из bundle по ключу для locale; при отсутствии — en; при отсутствии и там — null. */
    private String lookupTemplate(String bundleName, String key, String locale) {
        String fromLocale = bundleString(bundleName, key, locale);
        if (fromLocale != null) {
            return fromLocale;
        }
        if (locale != null && !FALLBACK_LANG.equalsIgnoreCase(locale)) {
            return bundleString(bundleName, key, FALLBACK_LANG);
        }
        return null;
    }

    private String bundleString(String bundleName, String key, String lang) {
        if (lang == null || lang.isBlank()) {
            return null;
        }
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(bundleName, Locale.of(lang),
                    EvidenceRenderer.class.getClassLoader(), NO_FALLBACK);
            // NO_FALLBACK всё равно может вернуть root <bundle>.properties как «base» — проверяем, что
            // bundle действительно для запрошенного языка, иначе наша цепочка locale→en сломается.
            if (!lang.equalsIgnoreCase(bundle.getLocale().getLanguage())) {
                return null;
            }
            return bundle.containsKey(key) ? bundle.getString(key) : null;
        } catch (MissingResourceException e) {
            return null; // нет bundle для языка
        }
    }

    /** Заменяет {@code {name}} на params[name]; списки → "a, b, c"; отсутствующий ключ → пусто. */
    private static String substitute(String template, Map<String, Object> params) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = params == null ? null : params.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(stringify(value)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof java.util.Collection<?> collection) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(item);
                first = false;
            }
            return sb.toString();
        }
        return String.valueOf(value);
    }
}
