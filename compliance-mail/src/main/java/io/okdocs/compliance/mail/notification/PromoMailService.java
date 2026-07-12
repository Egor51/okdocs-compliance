package io.okdocs.compliance.mail.notification;

import io.okdocs.compliance.mail.subscription.EmailSubscriptionService;
import io.okdocs.compliance.persistence.mail.EmailSubscription;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
public class PromoMailService {

    private final EmailSubscriptionService subscriptions;
    private final MailNotificationService notifications;

    public boolean enqueue(UUID campaignId, String email, String subject, String title,
                           String body, String actionUrl, String locale) {
        EmailSubscription subscription = subscriptions.activeByEmail(email).orElse(null);
        if (subscription == null) return false;
        Map<String, Object> model = new HashMap<>();
        model.put("title", title);
        model.put("body", body);
        model.put("actionUrl", actionUrl);
        model.put("unsubscribeUrl", subscriptions.unsubscribeUrl(subscription));
        notifications.enqueuePromo(campaignId, subscription.getId(), subscription.getEmail(),
                subject, model, locale == null ? subscription.getLocale() : locale);
        return true;
    }
}
