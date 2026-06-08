package io.okdocs.compliance.api.web;

import io.okdocs.compliance.api.security.CompliancePrincipal;
import io.okdocs.compliance.api.security.CurrentPrincipal;
import io.okdocs.compliance.api.service.ScanCommandService;
import io.okdocs.compliance.contracts.enums.ScanStatus;
import io.okdocs.compliance.contracts.scan.ScanEmailRequest;
import io.okdocs.compliance.contracts.scan.ScanListResponse;
import io.okdocs.compliance.contracts.scan.ScanReportResponse;
import io.okdocs.compliance.contracts.scan.ScanStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Чтение результатов скана (§4.1): статус/отчёт/история + сохранение email. Owner-check и
 * tier-маскировка — в сервисе. Запуск скана разнесён по двум flow:
 * {@code POST /api/free-scans} (marketing) и {@code POST /api/cabinet/scans} (premium).
 */
@RestController
@RequestMapping("/api/compliance-scans")
@RequiredArgsConstructor
public class ComplianceScanController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ScanCommandService scanCommandService;
    private final ClientIpResolver clientIpResolver;

    @GetMapping
    public ScanListResponse list(@RequestParam(required = false) String domain,
                                 @RequestParam(required = false) ScanStatus status,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        CompliancePrincipal principal = CurrentPrincipal.require();
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return scanCommandService.listScans(principal.userId(), domain, status, pageable);
    }

    @GetMapping("/{id}")
    public ScanStatusResponse status(@PathVariable UUID id) {
        return scanCommandService.getStatus(id, CurrentPrincipal.require());
    }

    @GetMapping("/{id}/report")
    public ScanReportResponse report(@PathVariable UUID id) {
        return scanCommandService.getReport(id, CurrentPrincipal.require());
    }

    @PostMapping("/{id}/email")
    public ResponseEntity<Void> saveEmail(@PathVariable UUID id,
                                          @Valid @RequestBody ScanEmailRequest request,
                                          HttpServletRequest http) {
        scanCommandService.saveEmail(id, request, clientIpResolver.resolve(http), CurrentPrincipal.require());
        return ResponseEntity.noContent().build();
    }
}
