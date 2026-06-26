package io.okdocs.compliance.contracts.crawler;

/**
 * Структура cookie-баннера / CMP, наблюдённая краулером во время DYNAMIC-рендера (§ PLAN-jurisdictions
 * Фаза 4). Вход для EU/UK consent-правил: «есть ли кнопка Reject», «равноценна ли она Accept»,
 * «есть ли предотмеченные тогглы». Все поля — структурные наблюдения краулера, без юрисдикционной
 * оценки (её делает правило).
 * <p>
 * {@code cmpProvider} — распознанная Consent Management Platform (Cookiebot, OneTrust, …) или
 * {@code null}. {@code rejectSameLevelAsAccept} — кнопка отказа присутствует и видимо равноценна
 * кнопке принятия (тот же уровень, не спрятана за «Manage») — ключевой сигнал CNIL/EDPB.
 */
public record ConsentBannerInfo(
        boolean bannerFound,
        boolean acceptButtonFound,
        boolean rejectButtonFound,
        boolean manageButtonFound,
        boolean savePreferencesFound,
        boolean rejectSameLevelAsAccept,
        boolean precheckedToggles,
        String cmpProvider
) {
    /** Баннер не обнаружен — все сигналы отрицательны. */
    public static ConsentBannerInfo notFound() {
        return new ConsentBannerInfo(false, false, false, false, false, false, false, null);
    }
}
