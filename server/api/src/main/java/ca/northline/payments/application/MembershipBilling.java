package ca.northline.payments.application;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-152): Northline Plus subscriptions. {@code northline.plus.billing.provider} ({@code
 * PLUS_BILLING_PROVIDER}) picks the adapter: {@code stripe} — Stripe Billing subscriptions on the platform account's
 * Customer, paid with the person's default saved card (S-59), statuses from webhooks; {@code local} — a fake in this
 * process (local and test; refused under staging and prod). Card data never reaches Northline.
 */
public interface MembershipBilling {

    /** {@code local} or {@code stripe} (the {@code loyalty.memberships.provider} value). */
    String provider();

    /**
     * @param paymentMethod the card to bill ({@code pm_…}), the customer's default when null
     * @param priceCents the plan's price — the fake charges it; Stripe bills its own Price
     * @param trialDays a first-time member's free days (0 = none)
     */
    record Subscribe(
            String userId,
            String householdId,
            String stripeCustomer,
            @Nullable String paymentMethod,
            String plan,
            long priceCents,
            int trialDays,
            String idempotencyKey) {}

    /**
     * @param status {@code incomplete | trialing | active | past_due | canceled} (Stripe's {@code unpaid} and
     *     {@code incomplete_expired} read as canceled, {@code paused} as past_due)
     * @param clientSecret when the first payment needs the customer (3-D Secure): the invoice's confirmation secret
     * @param paid the invoice paid with this call (the fake; Stripe's arrive by webhook)
     */
    record Subscription(
            String id,
            String status,
            String plan,
            @Nullable Instant currentPeriodEnd,
            boolean cancelAtPeriodEnd,
            @Nullable String clientSecret,
            @Nullable Invoice paid) {}

    record Invoice(String id, long amountCents, long taxCents, Instant paidAt) {}

    Subscription subscribe(Subscribe request);

    /**
     * The subscription as Northline last recorded it: Stripe reads only the id; the fake keeps no state of its own and
     * works from this.
     */
    record Current(
            String subscriptionId,
            String plan,
            String status,
            @Nullable Instant currentPeriodEnd,
            boolean cancelAtPeriodEnd) {}

    /** Monthly ↔ annual, prorated by the provider. */
    Subscription changePlan(Current current, String plan, long priceCents, String idempotencyKey);

    /** Cancel at the end of the period (true) or keep renewing (false). */
    Subscription cancelAtPeriodEnd(Current current, boolean cancel, String idempotencyKey);

    /**
     * The fake's renewal at the end of a period, with the plan's price (local only: Stripe renews on its own and says
     * so by webhook); empty when nothing is due or this adapter doesn't renew here.
     */
    Optional<Subscription> renew(Current current, long priceCents, Instant now);
}
