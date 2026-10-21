package ca.northline.payments.domain;

import java.time.Instant;
import lombok.With;
import org.jspecify.annotations.Nullable;

/**
 * S-162: one Northline Plus membership as Northline bills it ({@code payments.plus_schedules}): the plan's RFC 5545 rule
 * from {@code dtstart}, its price before tax, the card, and a plan change waiting for the next renewal.
 *
 * @param id the membership's billing id ({@code plus_<ULID>}, {@code loyalty.memberships.subscription_id})
 * @param province the tax's place of supply (the payer's home province at the join)
 * @param paymentMethod the card chosen for Plus ({@code pm_…}); null = the payer's default card
 * @param status {@code incomplete | trialing | active | past_due | canceled}
 * @param currentPeriodEnd the end of what was paid for (or of the trial)
 * @param endedReason {@code canceled | lapsed | refunded | unconfirmed} once {@code canceled}
 */
@With
public record PlusSchedule(
        String id,
        String householdId,
        String payerId,
        String plan,
        String rrule,
        Instant dtstart,
        long priceCents,
        String province,
        @Nullable String paymentMethod,
        @Nullable String nextPlan,
        @Nullable String nextRrule,
        @Nullable Long nextPriceCents,
        String status,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        @Nullable String endedReason,
        @Nullable Instant endedAt) {

    public static final String INCOMPLETE = "incomplete";
    public static final String TRIALING = "trialing";
    public static final String ACTIVE = "active";
    public static final String PAST_DUE = "past_due";
    public static final String CANCELED = "canceled";

    public BillingRule rule() {
        return new BillingRule(rrule, dtstart);
    }

    public boolean ended() {
        return CANCELED.equals(status);
    }

    /** The waiting plan change, applied from {@code start} (the renewal it waited for). */
    public PlusSchedule switchedAt(Instant start) {
        var next = nextPlan;
        var nextRule = nextRrule;
        var nextPrice = nextPriceCents;
        if (next == null || nextRule == null || nextPrice == null) {
            return this;
        }
        var anchored = BillingRule.anchored(nextRule, start);
        return new PlusSchedule(
                id,
                householdId,
                payerId,
                next,
                anchored.rrule(),
                anchored.dtstart(),
                nextPrice,
                province,
                paymentMethod,
                null,
                null,
                null,
                status,
                currentPeriodEnd,
                cancelAtPeriodEnd,
                endedReason,
                endedAt);
    }

    /** Ends the membership now. */
    public PlusSchedule end(String reason, Instant at) {
        return withStatus(CANCELED).withEndedReason(reason).withEndedAt(at);
    }
}
