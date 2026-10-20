package ca.northline.loyalty.domain;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * S-152: a household's Northline Plus membership ({@code loyalty.memberships}). Benefits apply while it is trialing or
 * active until the period's end, and while the provider retries a failed payment ({@code past_due}) for a grace
 * period after it; a cancelled one keeps them to the end of what was paid for.
 *
 * @param status {@code incomplete | trialing | active | past_due | canceled}
 * @param lastEventAt the provider event time last applied (an older webhook changes nothing)
 */
public record MembershipState(
        String householdId,
        String payerId,
        String plan,
        String status,
        String provider,
        @Nullable String subscriptionId,
        @Nullable Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        boolean trialUsed,
        Instant startedAt,
        @Nullable Instant canceledAt,
        @Nullable Instant lastEventAt) {

    public static final String PLAN_REQUIRED = "Choose monthly or annual.";
    public static final String ALREADY = "Northline Plus is already active for your household.";
    public static final String NONE = "Your household doesn't have Northline Plus.";
    public static final String NOT_PAYER = "Only the person who pays for Plus can change it.";
    public static final String SAME_PLAN = "That's already your plan.";

    public static boolean plan(@Nullable String plan) {
        return "monthly".equals(plan) || "annual".equals(plan);
    }

    /** Until when the benefits apply (null = not at all). */
    public @Nullable Instant benefitsUntil(Duration grace) {
        if (currentPeriodEnd == null) {
            return null;
        }
        return switch (status) {
            case "trialing", "active" -> currentPeriodEnd;
            case "past_due" -> currentPeriodEnd.plus(grace);
            default -> null;
        };
    }

    public boolean benefitsAt(Instant now, Duration grace) {
        var until = benefitsUntil(grace);
        return until != null && now.isBefore(until);
    }

    /** A membership that still runs (it can be changed or cancelled) — not ended, not waiting for its first payment. */
    public boolean running() {
        return status.equals("trialing") || status.equals("active") || status.equals("past_due");
    }
}
