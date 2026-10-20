package ca.northline.payments.infra;

import ca.northline.payments.application.MembershipBilling;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The local fake of Plus billing (S-152; {@code PLUS_BILLING_PROVIDER=local}, refused under staging and prod): nothing
 * leaves this process and it keeps no state (the membership row is the record). A subscription starts at once —
 * trialing for the trial's days, else active with its first invoice paid (the plan's configured price; the fake never
 * computes tax) — and the membership job renews it at the end of each period with another paid invoice, or ends it
 * when it was set to cancel. Ids {@code sub_local_…} / {@code in_local_…}.
 */
class LocalMembershipBilling implements MembershipBilling {

    private final Clock clock;

    LocalMembershipBilling(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String provider() {
        return "local";
    }

    static Duration period(String plan) {
        return "annual".equals(plan) ? Duration.ofDays(365) : Duration.ofDays(30);
    }

    @Override
    public Subscription subscribe(Subscribe r) {
        var now = clock.instant();
        var id = "sub_local_" + r.idempotencyKey().replaceAll("[^A-Za-z0-9]", "");
        var trial = r.trialDays() > 0;
        var end = trial ? now.plus(Duration.ofDays(r.trialDays())) : now.plus(period(r.plan()));
        return new Subscription(
                id,
                trial ? "trialing" : "active",
                r.plan(),
                end,
                false,
                null,
                trial ? null : invoice(id, r.priceCents(), now));
    }

    private static Invoice invoice(String subscriptionId, long cents, Instant at) {
        return new Invoice(
                "in_local_" + subscriptionId.substring("sub_local_".length()) + "_"
                        + at.atOffset(ZoneOffset.UTC).toLocalDate(),
                cents,
                0,
                at);
    }

    @Override
    public Subscription changePlan(Current c, String plan, long priceCents, String idempotencyKey) {
        // no proration in the fake: the new plan's price is charged at the next renewal
        return new Subscription(
                c.subscriptionId(), c.status(), plan, c.currentPeriodEnd(), c.cancelAtPeriodEnd(), null, null);
    }

    @Override
    public Subscription cancelAtPeriodEnd(Current c, boolean cancel, String idempotencyKey) {
        return new Subscription(c.subscriptionId(), c.status(), c.plan(), c.currentPeriodEnd(), cancel, null, null);
    }

    @Override
    public Optional<Subscription> renew(Current c, long priceCents, Instant now) {
        @Nullable Instant end = c.currentPeriodEnd();
        if (end == null || now.isBefore(end) || "canceled".equals(c.status())) {
            return Optional.empty();
        }
        if (c.cancelAtPeriodEnd()) {
            return Optional.of(new Subscription(c.subscriptionId(), "canceled", c.plan(), end, true, null, null));
        }
        return Optional.of(new Subscription(
                c.subscriptionId(),
                "active",
                c.plan(),
                end.plus(period(c.plan())),
                false,
                null,
                invoice(c.subscriptionId(), priceCents, end)));
    }
}
