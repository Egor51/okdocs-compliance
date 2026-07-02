package io.okdocs.compliance.contracts.exception;

/** Ресурс принадлежит другому пользователю — owner-check не пройден (→ HTTP 403). */
public class ForbiddenResourceException extends RuntimeException {

    public ForbiddenResourceException(String message) {
        super(message);
    }
}
