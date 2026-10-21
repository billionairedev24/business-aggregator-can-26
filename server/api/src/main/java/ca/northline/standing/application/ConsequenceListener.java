package ca.northline.standing.application;

import ca.northline.merchants.api.MerchantReinstated;
import ca.northline.merchants.api.MerchantSearchVisibilityChanged;
import ca.northline.merchants.api.MerchantSuspended;
import ca.northline.merchants.api.SellerDirectory;
import ca.northline.merchants.api.SellerSanctions;
import ca.northline.payments.api.EscrowReleaseDelayed;
import ca.northline.shared.Ids;
import ca.northline.standing.domain.Consequence;
import ca.northline.standing.domain.ConsequenceKind;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the registry in line with the switches other modules own: S-82's suspensions and search hiding (staff's and
 * the rules', with the reason from the oversight trail), and payments' photo delays. Idempotent: an active
 * consequence of a kind is recorded once, closing one that isn't active does nothing.
 */
@Component
@RequiredArgsConstructor
class ConsequenceListener {

    /** S-120's pilot hiding is a launch switch, not a trust consequence. */
    static final String PILOT = "pilot";

    private final ConsequenceService consequences;
    private final SellerDirectory directory;

    @ApplicationModuleListener
    void on(MerchantSuspended e) {
        var action = directory.action(e.actionId());
        var system = SellerSanctions.SYSTEM.equals(e.actorId());
        consequences.open(Consequence.ofBusiness(
                Ids.next(),
                e.aggregateId(),
                ConsequenceKind.SUSPENDED,
                system ? "off_platform_payment" : "staff",
                action.map(SellerDirectory.Oversight::reason).orElse("Suspended by Northline."),
                e.actionId(),
                Map.of(),
                e.actorId(),
                action.map(SellerDirectory.Oversight::actorRole).orElse(system ? Consequence.SYSTEM : "staff"),
                e.occurredAt(),
                null));
    }

    @ApplicationModuleListener
    void on(MerchantReinstated e) {
        var action = directory.action(e.actionId());
        consequences.closed(
                e.aggregateId(),
                ConsequenceKind.SUSPENDED,
                e.actorId(),
                action.map(SellerDirectory.Oversight::actorRole).orElse("staff"),
                action.map(SellerDirectory.Oversight::reason).orElse("Reinstated."));
    }

    @ApplicationModuleListener
    void on(MerchantSearchVisibilityChanged e) {
        if (PILOT.equals(e.cause())) {
            return;
        }
        var action = directory.action(e.actionId());
        var role = action.map(SellerDirectory.Oversight::actorRole).orElse("staff");
        var reason =
                action.map(SellerDirectory.Oversight::reason).orElse(e.hidden() ? "Hidden from search." : "Shown.");
        if (e.hidden()) {
            consequences.open(Consequence.ofBusiness(
                    Ids.next(),
                    e.aggregateId(),
                    ConsequenceKind.SEARCH_HIDDEN,
                    e.cause(),
                    reason,
                    e.actionId(),
                    Map.of(),
                    e.actorId(),
                    role,
                    e.occurredAt(),
                    null));
        } else {
            consequences.closed(e.aggregateId(), ConsequenceKind.SEARCH_HIDDEN, e.actorId(), role, reason);
        }
    }

    @ApplicationModuleListener
    void on(EscrowReleaseDelayed e) {
        consequences.open(Consequence.ofBusiness(
                Ids.next(),
                e.merchantId(),
                ConsequenceKind.PHOTO_DELAY,
                "missing_photo",
                ("The job was completed without a photo, so its payout is released %d hours later (Northline's rules:"
                                + " a completion photo protects you in a dispute).")
                        .formatted(e.hours()),
                e.aggregateId(),
                Map.of("bookingId", e.aggregateId(), "hours", Integer.toString(e.hours())),
                Consequence.SYSTEM,
                Consequence.SYSTEM,
                e.occurredAt(),
                e.delayedUntil()));
    }
}
