package ca.northline.payments.domain;

import java.time.Instant;
import java.util.Set;
import lombok.With;
import org.jspecify.annotations.Nullable;

/**
 * S-162: one period of a Plus membership ({@code payments.plus_periods}): the trial ({@code free}), or one occurrence of
 * the rule, charged at most once — one PaymentIntent per period, tried again on the retry schedule.
 *
 * @param state {@code free | scheduled | retrying | requires_action | paid | lapsed | void | refunded}
 * @param amountCents the plan's price before tax
 * @param attempts charge attempts so far (the first, the retries, the member's own confirmation)
 * @param nextAttemptAt when the renewal job tries (again); null when nothing is due
 * @param failure the last refusal: a Stripe decline code or {@code authentication_required}
 */
@With
public record PlusPeriod(
        String id,
        String scheduleId,
        int seq,
        Instant startsAt,
        Instant endsAt,
        String plan,
        String state,
        long amountCents,
        long taxCents,
        @Nullable String province,
        @Nullable String taxCalculation,
        @Nullable String taxTransaction,
        @Nullable String paymentIntent,
        int attempts,
        @Nullable Instant nextAttemptAt,
        @Nullable String failure,
        @Nullable Instant paidAt,
        @Nullable String refundId,
        @Nullable Instant refundedAt) {

    public static final String FREE = "free";
    public static final String SCHEDULED = "scheduled";
    public static final String RETRYING = "retrying";
    public static final String REQUIRES_ACTION = "requires_action";
    public static final String PAID = "paid";
    public static final String LAPSED = "lapsed";
    public static final String VOID = "void";
    public static final String REFUNDED = "refunded";

    private static final Set<String> OPEN = Set.of(SCHEDULED, RETRYING, REQUIRES_ACTION);

    /** A period still to be paid (due, being retried, or waiting for the member). */
    public boolean open() {
        return OPEN.contains(state);
    }

    public long totalCents() {
        return amountCents + taxCents;
    }

    /** The next period of the same plan: starts when this one ends, ends at the rule's next occurrence. */
    public static PlusPeriod scheduled(
            String id, String scheduleId, int seq, Instant startsAt, Instant endsAt, String plan, long amountCents) {
        return new PlusPeriod(
                id,
                scheduleId,
                seq,
                startsAt,
                endsAt,
                plan,
                SCHEDULED,
                amountCents,
                0,
                null,
                null,
                null,
                null,
                0,
                startsAt,
                null,
                null,
                null,
                null);
    }
}
