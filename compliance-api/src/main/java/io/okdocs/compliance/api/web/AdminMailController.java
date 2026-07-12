package io.okdocs.compliance.api.web;

import io.okdocs.compliance.contracts.admin.AdminPromoEmailRequest;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.mail.notification.PromoMailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/mail")
@RequiredArgsConstructor
public class AdminMailController {

    private final PromoMailService promoMailService;

    @PostMapping("/promo")
    public ResponseEntity<Void> enqueuePromo(@Valid @RequestBody AdminPromoEmailRequest request) {
        boolean queued = promoMailService.enqueue(
                request.campaignId(), request.email(), request.subject(), request.title(),
                request.body(), request.actionUrl(), request.locale());
        if (!queued) {
            throw new ComplianceValidationException("Адрес не имеет активного согласия на рассылку");
        }
        return ResponseEntity.accepted().build();
    }
}
