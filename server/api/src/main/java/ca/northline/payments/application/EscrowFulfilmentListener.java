package ca.northline.payments.application;

import ca.northline.booking.api.BookingProgressed.BookingCompleted;
import ca.northline.payments.api.EscrowLifecycle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Starts the escrow release clock when a job is completed (CLAUDE.md: services release 48 h after completion). Food
 * handoff is wired from the food module ({@code food.application.KitchenEscrowRelease}) to keep module dependencies
 * acyclic. Money is only held when the customer's payment was authorized, so work without an escrow (e.g. seeded
 * or cash-free test data) is skipped rather than failed. {@link EscrowLifecycle} is idempotent, so retried
 * publications are safe.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class EscrowFulfilmentListener {

    static final String BOOKING = "booking";

    private final EscrowLifecycle escrow;

    @ApplicationModuleListener
    void on(BookingCompleted event) {
        fulfil(BOOKING, event.aggregateId(), event);
    }

    private void fulfil(String refType, String refId, ca.northline.shared.DomainEvent event) {
        if (!escrow.fulfilledIfHeld(refType, refId, event.occurredAt())) {
            log.debug(
                    "No escrow for {}:{} ({}); nothing to release",
                    refType,
                    refId,
                    event.getClass().getSimpleName());
        }
    }
}
