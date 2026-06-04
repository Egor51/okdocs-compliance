package io.okdocs.compliance.contracts.exception;

/** У юзера нет доступных сканов на балансе (→ HTTP 402/409). */
public class InsufficientScanBalanceException extends RuntimeException {

    private final Long userId;

    public InsufficientScanBalanceException(Long userId) {
        super("Insufficient scan balance for user: " + userId);
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}
