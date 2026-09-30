package ca.northline.payments.application;

import ca.northline.shared.CodedEnum;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A Stripe event whose signature was verified.
 *
 * @param account the connected account ({@code acct_…}) for events from the Connect endpoint, else null
 * @param created when Stripe created the event — the order events are applied in
 * @param object the event's {@code data.object}
 */
public record StripeEvent(
        String id,
        String type,
        Endpoint endpoint,
        @Nullable String account,
        boolean livemode,
        Instant created,
        StripeObject object) {

    /** Which webhook endpoint received it: the platform's own events, or its connected accounts'. */
    public enum Endpoint implements CodedEnum {
        PLATFORM,
        CONNECT
    }
}
