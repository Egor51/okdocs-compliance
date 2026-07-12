package io.okdocs.compliance.api.web;

import io.okdocs.compliance.contracts.auth.UnsubscribeRequest;
import io.okdocs.compliance.mail.subscription.EmailSubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
public class MailController {

    private final EmailSubscriptionService subscriptions;

    @PostMapping("/unsubscribe")
    public ResponseEntity<Void> unsubscribe(@Valid @RequestBody UnsubscribeRequest request) {
        try {
            subscriptions.unsubscribe(request.token());
        } catch (IllegalArgumentException ignored) {
            // Идемпотентный непрозрачный ответ: не раскрываем существование подписки.
        }
        return ResponseEntity.noContent().build();
    }
}
