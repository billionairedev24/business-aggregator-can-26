package ca.northline.restricted.application;

import ca.northline.payments.api.IdentitySessionUpdated;
import ca.northline.restricted.application.AgeVerificationUseCases.ApplyAgeSession;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Stripe Identity webhooks, verified and de-duplicated by payments' Stripe endpoint (S-12), also finish customers' age
 * checks. A session that isn't an open age check (a business owner's, S-22) is ignored here, as the merchants module
 * ignores ours. Runs after commit, retried from the event registry; applying twice changes nothing.
 */
@Component
@RequiredArgsConstructor
class AgeSessionUpdates {

    private final ApplyAgeSession sessions;

    @ApplicationModuleListener
    void on(IdentitySessionUpdated event) {
        if (event.merchantId() != null) {
            return; // an owner's identity check (metadata northline_merchant_id)
        }
        sessions.apply(event.aggregateId(), event.status(), event.lastErrorCode());
    }
}
