package io.okdocs.compliance.rules.uk;

import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.rules.RuleDefinition;

import java.util.List;

/**
 * UK-слой метаданных для shared common-детекторов (§ PLAN-jurisdictions Фаза 6). UK <b>не</b>
 * наследует EU baseline: common-детекторы ({@code rules.common}, {@code supportedLayers={RU,EU,UK}})
 * на UK-скане без этих overlay-записей вернули бы пусто (own-метаданные — RU). Под теми же
 * {@code code} регистрируются standalone-{@link RuleDefinition} с {@code jurisdiction = UK}.
 * <p>
 * Legal basis — UK GDPR Art. 32 (security of processing) для headers/TLS и PECR reg. 6 (storage of
 * information) + UK GDPR для cookie/storage. Severity/category совпадают с common-правилом.
 */
public final class UkCommonMetadata {

    private UkCommonMetadata() {
    }

    private static final String ART_32 = "UK GDPR Art. 32 (security of processing); DPA 2018";
    private static final String PECR = "PECR 2003 reg. 6 (confidentiality of communications); UK GDPR Art. 6";

    public static List<RuleDefinition> entries() {
        return List.of(
                security("MISSING_HSTS", FindingSeverity.MEDIUM,
                        "HSTS header (Strict-Transport-Security) not set",
                        "HTTPS responses do not send Strict-Transport-Security, leaving a window for "
                                + "downgrade / MITM when transmitting personal data. UK GDPR Art. 32 requires "
                                + "appropriate transport security.",
                        "Enable Strict-Transport-Security on HTTPS responses (max-age + includeSubDomains).",
                        "HSTS configured",
                        "Strict-Transport-Security is present on the checked HTTPS responses."),
                security("MISSING_CSP", FindingSeverity.MEDIUM,
                        "Content-Security-Policy header not set",
                        "No Content-Security-Policy header; CSP reduces XSS risk that could exfiltrate "
                                + "personal data (UK GDPR Art. 32).",
                        "Set a Content-Security-Policy restricting script/style sources; start report-only.",
                        "Content-Security-Policy configured",
                        "A Content-Security-Policy header is present on the checked pages."),
                security("WEAK_CSP", FindingSeverity.LOW,
                        "Weak Content-Security-Policy",
                        "The Content-Security-Policy is permissive (unsafe-inline / wildcards), reducing "
                                + "its protection against XSS (UK GDPR Art. 32).",
                        "Remove unsafe-inline/unsafe-eval and wildcard sources; use nonces or hashes.",
                        "Content-Security-Policy is robust",
                        "The Content-Security-Policy does not use weak directives."),
                security("WILDCARD_CORS", FindingSeverity.MEDIUM,
                        "Permissive CORS (Access-Control-Allow-Origin: *)",
                        "A wildcard Access-Control-Allow-Origin can expose personal data to untrusted "
                                + "origins (UK GDPR Art. 32).",
                        "Restrict Access-Control-Allow-Origin to an explicit allowlist of trusted origins.",
                        "CORS is restricted",
                        "No wildcard CORS was detected."),
                security("HTTPS_NOT_ENFORCED", FindingSeverity.HIGH,
                        "HTTPS is not enforced",
                        "The site does not enforce HTTPS, so personal data may travel in clear text "
                                + "(UK GDPR Art. 32).",
                        "Redirect all HTTP to HTTPS, serve forms over HTTPS, enable HSTS.",
                        "HTTPS is enforced",
                        "The site redirects to HTTPS and serves content securely."),
                security("MIXED_CONTENT_DETECTED", FindingSeverity.MEDIUM,
                        "Mixed content on HTTPS pages",
                        "HTTPS pages load HTTP sub-resources, weakening the secure channel (UK GDPR Art. 32).",
                        "Load all sub-resources over HTTPS; add upgrade-insecure-requests.",
                        "No mixed content",
                        "HTTPS pages do not load insecure sub-resources."),
                security("TLS_CERTIFICATE_INVALID", FindingSeverity.HIGH,
                        "Invalid TLS certificate",
                        "The TLS certificate is invalid (untrusted/mismatch/expired); the encrypted "
                                + "channel for personal data cannot be relied upon (UK GDPR Art. 32).",
                        "Install a valid certificate from a trusted CA matching the hostname.",
                        "TLS certificate is valid",
                        "The TLS certificate is trusted and matches the hostname."),
                security("TLS_CERTIFICATE_EXPIRES_SOON", FindingSeverity.MEDIUM,
                        "TLS certificate expires soon",
                        "The TLS certificate expires within 30 days; expiry breaks the secure channel "
                                + "(UK GDPR Art. 32).",
                        "Renew the certificate before expiry and automate renewal.",
                        "TLS certificate validity is sufficient",
                        "The TLS certificate is not close to expiry."),
                cookies("TRACKING_COOKIES_BEFORE_CONSENT", FindingSeverity.HIGH,
                        "Tracking cookies set before consent",
                        "Tracking cookies are stored before consent. PECR reg. 6 requires prior consent "
                                + "for non-essential cookies.",
                        "Block non-essential cookies until the user consents.",
                        "No tracking cookies before consent",
                        "No non-essential tracking cookies were observed before consent."),
                cookies("LOCAL_STORAGE_TRACKING_BEFORE_CONSENT", FindingSeverity.MEDIUM,
                        "Tracking in local storage before consent",
                        "Tracking identifiers are written to web storage before consent. PECR reg. 6 "
                                + "covers any storage/access on the user's device.",
                        "Defer writing tracking identifiers to storage until consent.",
                        "No tracking storage before consent",
                        "No tracking identifiers were observed in web storage before consent."),
                cookies("COOKIE_WITHOUT_SECURE_FLAG", FindingSeverity.MEDIUM,
                        "Cookie without Secure flag",
                        "A cookie is set without Secure, so it may travel over HTTP — weakening "
                                + "protection of any personal data it carries (UK GDPR Art. 32).",
                        "Set Secure (and SameSite) on cookies; never send session cookies over HTTP.",
                        "Cookies use the Secure flag",
                        "Cookies set by the site use the Secure attribute."),
                cookies("SESSION_COOKIE_WITHOUT_HTTPONLY", FindingSeverity.HIGH,
                        "Session cookie without HttpOnly",
                        "A session cookie lacks HttpOnly, exposing it to XSS theft and session hijacking "
                                + "(UK GDPR Art. 32).",
                        "Set HttpOnly (and Secure, SameSite) on session cookies.",
                        "Session cookies use HttpOnly",
                        "Session cookies set by the site use the HttpOnly attribute."));
    }

    private static RuleDefinition security(String code, FindingSeverity severity, String title,
                                           String explanation, String recommendation,
                                           String positiveTitle, String positiveMessage) {
        return new RuleDefinition(code, ScanJurisdiction.UK, severity, FindingCategory.SECURITY,
                title, null, ART_32, explanation, recommendation, positiveTitle, positiveMessage);
    }

    private static RuleDefinition cookies(String code, FindingSeverity severity, String title,
                                          String explanation, String recommendation,
                                          String positiveTitle, String positiveMessage) {
        return new RuleDefinition(code, ScanJurisdiction.UK, severity, FindingCategory.COOKIES,
                title, null, PECR, explanation, recommendation, positiveTitle, positiveMessage);
    }
}
