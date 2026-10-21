package ca.northline.payments.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import java.util.List;

/**
 * S-156: a job was completed without a completion photo, so its escrow (and a deposit quote's balance) is released
 * {@code hours} later than the services clock would, not before {@code delayedUntil}. In-process only (not
 * externalized): the standing module shows it to the business as a consequence it can ask staff to lift.
 *
 * @param aggregateId the booking id
 */
public record EscrowReleaseDelayed(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        List<String> escrowIds,
        int hours,
        Instant delayedUntil)
        implements DomainEvent {

    public EscrowReleaseDelayed {
        escrowIds = List.copyOf(escrowIds);
    }
}
