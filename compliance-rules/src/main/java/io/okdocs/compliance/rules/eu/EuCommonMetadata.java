package io.okdocs.compliance.rules.eu;

import io.okdocs.compliance.contracts.enums.FindingCategory;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import io.okdocs.compliance.rules.RuleDefinition;

import java.util.List;

/**
 * EU-слой метаданных для shared common-детекторов (§ PLAN-jurisdictions Фаза 3). Common-правила
 * ({@code rules.common}) несут own-метаданные слоя RU (152-ФЗ); чтобы их находки появлялись на
 * EU/DE/FR/ES-сканах с корректным GDPR-обоснованием, под теми же {@code code} регистрируются эти
 * standalone-{@link RuleDefinition} с {@code jurisdiction = EU}. Резолвер метаданных накладывает их
 * по {@code (code, EU)} (см. {@code RuleMetadataResolver.overlayMetadata}).
 * <p>
 * Severity/category совпадают с common-правилом (тот же технический факт); меняются только
 * legalBasis/explanation/recommendation/тексты — на GDPR-формулировки. Security-headers/TLS → Art. 32
 * (security of processing); cookie/storage до согласия → ePrivacy Art. 5(3) + GDPR Art. 6.
 */
public final class EuCommonMetadata {

    private EuCommonMetadata() {
    }

    private static final String ART_32 = "GDPR Art. 32 (security of processing)";
    private static final String EPRIVACY = "ePrivacy Directive 2002/58/EC Art. 5(3); GDPR Art. 6";

    public static List<RuleDefinition> entries() {
        return List.of(
                security("MISSING_HSTS", FindingSeverity.MEDIUM,
                        "HSTS header (Strict-Transport-Security) not set",
                        "The HTTPS responses do not send Strict-Transport-Security, leaving a window for "
                                + "protocol downgrade / MITM when transmitting personal data. Under GDPR Art. 32 "
                                + "controllers must implement appropriate transport security.",
                        "Enable Strict-Transport-Security on HTTPS responses, e.g. max-age=31536000; "
                                + "includeSubDomains (verify all subdomains are HTTPS first).",
                        "HSTS configured",
                        "Strict-Transport-Security is present on the checked HTTPS responses."),
                security("MISSING_CSP", FindingSeverity.MEDIUM,
                        "Content-Security-Policy header not set",
                        "No Content-Security-Policy header. CSP limits script/resource origins, reducing "
                                + "XSS risk that could exfiltrate personal data. Its absence weakens the security "
                                + "of processing under GDPR Art. 32.",
                        "Set a Content-Security-Policy restricting script/style sources to trusted origins; "
                                + "start in report-only mode, then enforce.",
                        "Content-Security-Policy configured",
                        "A Content-Security-Policy header is present on the checked pages."),
                security("WEAK_CSP", FindingSeverity.LOW,
                        "Weak Content-Security-Policy",
                        "The Content-Security-Policy is present but permissive (e.g. unsafe-inline / "
                                + "wildcard sources), reducing its protective value against XSS under GDPR Art. 32.",
                        "Tighten the CSP: remove unsafe-inline/unsafe-eval and wildcard sources; use nonces "
                                + "or hashes for inline scripts.",
                        "Content-Security-Policy is robust",
                        "The Content-Security-Policy does not use weak directives on the checked pages."),
                security("WILDCARD_CORS", FindingSeverity.MEDIUM,
                        "Permissive CORS (Access-Control-Allow-Origin: *)",
                        "A wildcard Access-Control-Allow-Origin allows any origin to read responses, which "
                                + "can expose personal data to untrusted sites — inadequate security of processing "
                                + "under GDPR Art. 32.",
                        "Restrict Access-Control-Allow-Origin to an explicit allowlist of trusted origins; "
                                + "avoid '*' on responses carrying personal data.",
                        "CORS is restricted",
                        "No wildcard CORS was detected on the checked responses."),
                security("HTTPS_NOT_ENFORCED", FindingSeverity.HIGH,
                        "HTTPS is not enforced",
                        "The site does not enforce HTTPS (no redirect from HTTP, or forms posting over "
                                + "HTTP). Personal data may be transmitted in clear text — a transport-security "
                                + "failure under GDPR Art. 32.",
                        "Redirect all HTTP to HTTPS, serve all forms over HTTPS, and enable HSTS.",
                        "HTTPS is enforced",
                        "The site redirects to HTTPS and serves content securely."),
                security("MIXED_CONTENT_DETECTED", FindingSeverity.MEDIUM,
                        "Mixed content on HTTPS pages",
                        "HTTPS pages load sub-resources over HTTP (mixed content), weakening the secure "
                                + "channel through which personal data is transmitted (GDPR Art. 32).",
                        "Load all sub-resources over HTTPS; fix or remove HTTP references and add an "
                                + "upgrade-insecure-requests directive.",
                        "No mixed content",
                        "HTTPS pages do not load insecure sub-resources."),
                security("TLS_CERTIFICATE_INVALID", FindingSeverity.HIGH,
                        "Invalid TLS certificate",
                        "The TLS certificate is invalid (untrusted chain, hostname mismatch, or expired). "
                                + "Users cannot rely on the encrypted channel for personal data — a security-of-"
                                + "processing failure under GDPR Art. 32.",
                        "Install a valid certificate from a trusted CA matching the hostname; automate "
                                + "renewal.",
                        "TLS certificate is valid",
                        "The TLS certificate is trusted and matches the hostname."),
                security("TLS_CERTIFICATE_EXPIRES_SOON", FindingSeverity.MEDIUM,
                        "TLS certificate expires soon",
                        "The TLS certificate expires within 30 days. An expired certificate breaks the "
                                + "secure channel for personal data (GDPR Art. 32).",
                        "Renew the certificate before expiry and automate renewal/monitoring.",
                        "TLS certificate validity is sufficient",
                        "The TLS certificate is not close to expiry."),
                cookies("TRACKING_COOKIES_BEFORE_CONSENT", FindingSeverity.HIGH,
                        "Tracking cookies set before consent",
                        "Tracking cookies are stored before the user gives consent. Under ePrivacy Art. "
                                + "5(3) non-essential cookies require prior consent; setting them beforehand is "
                                + "unlawful.",
                        "Block non-essential cookies until the user consents; load tag managers / analytics "
                                + "only after an affirmative opt-in.",
                        "No tracking cookies before consent",
                        "No non-essential tracking cookies were observed before consent."),
                cookies("LOCAL_STORAGE_TRACKING_BEFORE_CONSENT", FindingSeverity.MEDIUM,
                        "Tracking in local storage before consent",
                        "Tracking identifiers are written to web storage before consent. ePrivacy Art. 5(3) "
                                + "covers any storage/access on the user's device, not just cookies.",
                        "Defer writing tracking identifiers to local/session storage until the user "
                                + "consents.",
                        "No tracking storage before consent",
                        "No tracking identifiers were observed in web storage before consent."),
                cookies("COOKIE_WITHOUT_SECURE_FLAG", FindingSeverity.MEDIUM,
                        "Cookie without Secure flag",
                        "A cookie is set without the Secure attribute, so it may be transmitted over "
                                + "unencrypted HTTP — weakening protection of any personal data it carries "
                                + "(GDPR Art. 32).",
                        "Set the Secure attribute on cookies (and SameSite); never send session cookies "
                                + "over HTTP.",
                        "Cookies use the Secure flag",
                        "Cookies set by the site use the Secure attribute."),
                cookies("SESSION_COOKIE_WITHOUT_HTTPONLY", FindingSeverity.HIGH,
                        "Session cookie without HttpOnly",
                        "A session cookie is set without HttpOnly, exposing it to theft via XSS and risking "
                                + "session hijacking / unauthorised access to personal data (GDPR Art. 32).",
                        "Set HttpOnly (and Secure, SameSite) on session cookies.",
                        "Session cookies use HttpOnly",
                        "Session cookies set by the site use the HttpOnly attribute."));
    }

    private static RuleDefinition security(String code, FindingSeverity severity, String title,
                                           String explanation, String recommendation,
                                           String positiveTitle, String positiveMessage) {
        return new RuleDefinition(code, ScanJurisdiction.EU, severity, FindingCategory.SECURITY,
                title, null, ART_32, explanation, recommendation, positiveTitle, positiveMessage);
    }

    private static RuleDefinition cookies(String code, FindingSeverity severity, String title,
                                          String explanation, String recommendation,
                                          String positiveTitle, String positiveMessage) {
        return new RuleDefinition(code, ScanJurisdiction.EU, severity, FindingCategory.COOKIES,
                title, null, EPRIVACY, explanation, recommendation, positiveTitle, positiveMessage);
    }
}
