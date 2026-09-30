package ca.northline.merchants.application;

import ca.northline.merchants.application.OwnerIdentity.ApplyIdentitySession;
import ca.northline.merchants.domain.IdentitySessionState;
import ca.northline.payments.api.IdentitySessionUpdated;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Stripe Identity webhooks (S-22), verified and de-duplicated by payments' Stripe endpoint (S-12), update the owner's
 * check and the {@code kyc} row. Runs after commit in its own transaction and is retried from the event registry;
 * applying the same update twice changes nothing (sessions only move forward in Stripe's event order).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class StripeIdentityUpdates {

    private final ApplyIdentitySession sessions;

    @ApplicationModuleListener
    void on(IdentitySessionUpdated event) {
        var state = Arrays.stream(IdentitySessionState.values())
                .filter(s -> s.code().equals(event.status()))
                .findFirst()
                .orElse(null);
        if (state == null) {
            log.info("Stripe Identity session {} has unknown status {}; ignored", event.aggregateId(), event.status());
            return;
        }
        sessions.apply(
                new ApplyIdentitySession.Update(event.aggregateId(), state, event.lastErrorCode(), event.occurredAt()));
    }
}
