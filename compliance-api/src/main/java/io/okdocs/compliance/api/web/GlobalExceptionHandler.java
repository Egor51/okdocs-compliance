package io.okdocs.compliance.api.web;

import io.okdocs.compliance.contracts.exception.AccessDeniedToScanException;
import io.okdocs.compliance.contracts.exception.ComplianceRateLimitException;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.exception.ForbiddenResourceException;
import io.okdocs.compliance.contracts.exception.InsufficientScanBalanceException;
import io.okdocs.compliance.contracts.exception.ScanNotFoundException;
import io.okdocs.compliance.contracts.exception.ScanReportNotReadyException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/** Маппинг доменных исключений в HTTP-коды (§ PROJECT «Коды ответов»). */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ScanNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ScanNotFoundException e, HttpServletRequest req) {
        return error(HttpStatus.NOT_FOUND, e.getMessage(), req);
    }

    @ExceptionHandler(ScanReportNotReadyException.class)
    public ResponseEntity<Map<String, Object>> handleReportNotReady(ScanReportNotReadyException e,
                                                                    HttpServletRequest req) {
        return error(HttpStatus.CONFLICT, e.getMessage(), req);
    }

    @ExceptionHandler(AccessDeniedToScanException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(AccessDeniedToScanException e, HttpServletRequest req) {
        return error(HttpStatus.FORBIDDEN, e.getMessage(), req);
    }

    @ExceptionHandler(ForbiddenResourceException.class)
    public ResponseEntity<Map<String, Object>> handleForbiddenResource(ForbiddenResourceException e, HttpServletRequest req) {
        return error(HttpStatus.FORBIDDEN, e.getMessage(), req);
    }

    @ExceptionHandler(InsufficientScanBalanceException.class)
    public ResponseEntity<Map<String, Object>> handleNoBalance(InsufficientScanBalanceException e, HttpServletRequest req) {
        return error(HttpStatus.PAYMENT_REQUIRED, e.getMessage(), req);
    }

    @ExceptionHandler(ComplianceRateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(ComplianceRateLimitException e, HttpServletRequest req) {
        return error(HttpStatus.TOO_MANY_REQUESTS, e.getMessage(), req);
    }

    @ExceptionHandler(ComplianceValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(ComplianceValidationException e, HttpServletRequest req) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleBeanValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, message.isBlank() ? "Ошибка валидации" : message, req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e, HttpServletRequest req) {
        log.error("Необработанная ошибка на {} {}", req.getMethod(), req.getRequestURI(), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка сервера", req);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message, HttpServletRequest req) {
        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? "" : message,
                "path", req.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
