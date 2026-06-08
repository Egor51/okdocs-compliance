package io.okdocs.compliance.api.web;

import io.okdocs.compliance.api.security.CompliancePrincipal;
import io.okdocs.compliance.api.security.CurrentPrincipal;
import io.okdocs.compliance.api.service.ScanCommandService;
import io.okdocs.compliance.contracts.scan.FreeScanRequest;
import io.okdocs.compliance.contracts.scan.ScanStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Бесплатный маркетинговый скан ({@code POST /api/free-scans}) — публичный лид-магнит: проверяет
 * главную страницу (1 страница, static-only), баланс не трогает. Доступен анонимно (guest-токен
 * выдаётся прозрачно) и авторизованным. Результат читается через {@code /api/compliance-scans/{id}}.
 */
@RestController
@RequestMapping("/api/free-scans")
@RequiredArgsConstructor
public class FreeScanController {

    private final ScanCommandService scanCommandService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping
    public ResponseEntity<ScanStatusResponse> start(@Valid @RequestBody FreeScanRequest request,
                                                    HttpServletRequest http) {
        CompliancePrincipal principal = CurrentPrincipal.require();
        ScanStatusResponse response = scanCommandService.startFreeScan(
                request, clientIpResolver.resolve(http), principal);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
