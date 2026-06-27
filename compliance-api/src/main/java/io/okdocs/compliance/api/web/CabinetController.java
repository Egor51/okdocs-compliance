package io.okdocs.compliance.api.web;

import io.okdocs.compliance.api.security.CompliancePrincipal;
import io.okdocs.compliance.api.security.CurrentPrincipal;
import io.okdocs.compliance.api.service.AuthService;
import io.okdocs.compliance.api.service.BalanceTransactionMapper;
import io.okdocs.compliance.api.service.CheckoutService;
import io.okdocs.compliance.api.service.DashboardService;
import io.okdocs.compliance.api.service.ScanBalanceService;
import io.okdocs.compliance.api.service.ScanCommandService;
import io.okdocs.compliance.contracts.auth.ChangePasswordRequest;
import io.okdocs.compliance.contracts.cabinet.BalanceTransactionDto;
import io.okdocs.compliance.contracts.cabinet.ScanBalanceDto;
import io.okdocs.compliance.contracts.cabinet.UserDashboardResponse;
import io.okdocs.compliance.contracts.payment.CheckoutRequest;
import io.okdocs.compliance.contracts.payment.CheckoutResponse;
import io.okdocs.compliance.contracts.scan.ScanRequest;
import io.okdocs.compliance.contracts.scan.ScanStatusResponse;
import io.okdocs.compliance.persistence.billing.ScanBalanceTransaction;
import io.okdocs.compliance.persistence.billing.ScanBalanceTransactionRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Кабинет юзера (§4.1, только USER): premium-скан, дашборд, баланс, леджер, смена пароля. */
@RestController
@RequestMapping("/api/cabinet")
@RequiredArgsConstructor
public class CabinetController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DashboardService dashboardService;
    private final ScanBalanceService balanceService;
    private final AuthService authService;
    private final ScanCommandService scanCommandService;
    private final CheckoutService checkoutService;
    private final ClientIpResolver clientIpResolver;
    private final ScanBalanceTransactionRepository txnRepository;
    private final BalanceTransactionMapper txnMapper;

    /**
     * Premium-скан кабинета: полный crawl + dynamic, списывает 1 кредит баланса (нет → 402).
     * При FAILED worker вернёт кредит (refund). Только USER (зона {@code /api/cabinet/**}).
     */
    @PostMapping("/scans")
    public ResponseEntity<ScanStatusResponse> startScan(@Valid @RequestBody ScanRequest request,
                                                        HttpServletRequest http) {
        CompliancePrincipal principal = CurrentPrincipal.require();
        ScanStatusResponse response = scanCommandService.startCabinetScan(
                request, clientIpResolver.resolve(http), principal);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * Создать checkout-сессию оплаты premium-скана (F.4 §F12). От authenticated USER (userId из JWT).
     * Активация (баланс/скан) — НЕ здесь, а по webhook'у после оплаты. Возвращает confirmationUrl.
     */
    @PostMapping("/checkout")
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest request) {
        Long userId = CurrentPrincipal.require().userId();
        return checkoutService.createCheckout(userId, request);
    }

    @GetMapping
    public UserDashboardResponse dashboard() {
        return dashboardService.getDashboard(CurrentPrincipal.require().userId());
    }

    @GetMapping("/balance")
    public ScanBalanceDto balance() {
        return balanceService.getBalance(CurrentPrincipal.require().userId());
    }

    @GetMapping("/balance/transactions")
    public List<BalanceTransactionDto> transactions(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        Long userId = CurrentPrincipal.require().userId();
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        Page<ScanBalanceTransaction> txns = txnRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return txnMapper.toDtos(txns.getContent());
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(CurrentPrincipal.require().userId(), request);
        return ResponseEntity.noContent().build();
    }
}
