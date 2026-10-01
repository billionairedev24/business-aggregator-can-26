package ca.northline.merchants.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A Northline agent decided a submitted application in the console's verification queue (S-79): {@code approved}
 * (the business is active — {@link MerchantApproved} is published too) or {@code info_requested} (sent back to the
 * owner with the checks to redo). In-process only: the messaging module emails the owners. The note is the agent's
 * message to the business, written for it; no personal data.
 *
 * @param aggregateId the merchant id
 * @param decision {@code approved | info_requested}
 * @param checkKeys checklist keys to redo ({@code insurance}, {@code licence:AMVIC}); empty when approved
 */
public record ApplicationDecided(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String actorId,
        String decision,
        List<String> checkKeys,
        @Nullable String note)
        implements DomainEvent {
    public ApplicationDecided {
        checkKeys = List.copyOf(checkKeys);
    }
}
