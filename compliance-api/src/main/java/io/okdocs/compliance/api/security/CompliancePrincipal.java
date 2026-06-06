package io.okdocs.compliance.api.security;

import io.okdocs.compliance.contracts.enums.PrincipalType;
import io.okdocs.compliance.contracts.enums.UserRole;

import java.util.UUID;

/**
 * Аутентифицированный субъект запроса — кладётся в {@code Authentication.getPrincipal()}.
 * <ul>
 *   <li>GUEST → {@code guestId} заполнен, {@code userId}/{@code role} = null;</li>
 *   <li>USER/ADMIN → {@code userId}/{@code role} заполнены, {@code guestId} = null.</li>
 * </ul>
 */
public record CompliancePrincipal(
        PrincipalType type,
        Long userId,
        UUID guestId,
        UserRole role
) {

    public static CompliancePrincipal guest(UUID guestId) {
        return new CompliancePrincipal(PrincipalType.GUEST, null, guestId, null);
    }

    public static CompliancePrincipal user(Long userId, UserRole role) {
        PrincipalType type = role == UserRole.ADMIN ? PrincipalType.ADMIN : PrincipalType.USER;
        return new CompliancePrincipal(type, userId, null, role);
    }

    public boolean isUser() {
        return userId != null;
    }
}
