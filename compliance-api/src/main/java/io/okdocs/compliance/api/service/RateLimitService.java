package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.security.CompliancePrincipal;

/**
 * Анти-абьюз лимит по частоте сканов (§4.2), независим от биллинг-квоты.
 * Ключ зависит от типа принципала:
 * <ul>
 *   <li>GUEST → {@code ip:<ip>}, лимит {@code guestScansPerIpPerHour};</li>
 *   <li>USER → {@code user:<userId>}, лимит {@code userScansPerHour}, плюс грубый потолок
 *       {@code ip:<ip>} как нижний слой.</li>
 * </ul>
 */
public interface RateLimitService {

    /**
     * Пытается потребить 1 «слот» для скана. Бросает {@code ComplianceRateLimitException},
     * если лимит исчерпан (→ HTTP 429).
     */
    void checkScanAllowed(CompliancePrincipal principal, String ipAddress);
}
