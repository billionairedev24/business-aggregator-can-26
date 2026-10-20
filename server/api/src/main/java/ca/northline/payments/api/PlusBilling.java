package ca.northline.payments.api;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * S-152: billing Northline Plus for the loyalty module, which owns the membership. Payments owns the money: the
 * person's Stripe Customer and default card (S-59), the subscription at the provider ({@code MembershipBilling}), the
 * invoices it collects (ledger {@code revenue} + {@code tax_payable}, ref {@code plus_invoice}) and the webhooks that
 * change it ({@link PlusSubscriptionUpdated}). A subscription needs a saved card first (409 {@code card_required}).
 */
public interface PlusBilling {

    String NO_CARD = "Add a card in Payment methods first: Plus renews on it.";

    /** {@code local} or {@code stripe}. */
    String provider();

    /**
     * @param clientSecret when the first payment needs the customer (3-D Secure) — confirm it with Stripe.js
     * @param publishableKey Stripe.js's key when {@code clientSecret} is set
     */
    record Billed(
            String subscriptionId,
            String status,
            String plan,
            @Nullable Instant currentPeriodEnd,
            boolean cancelAtPeriodEnd,
            @Nullable String clientSecret,
            @Nullable String publishableKey) {}

    /** The membership as the loyalty module recorded it. */
    record Current(
            String subscriptionId,
            String householdId,
            String plan,
            String status,
            @Nullable Instant currentPeriodEnd,
            boolean cancelAtPeriodEnd) {}

    /** @param trialDays free days (0 for a household that had Plus before) */
    Billed subscribe(
            String userId, String householdId, String plan, long priceCents, int trialDays, String idempotencyKey);

    Billed changePlan(Current current, String plan, long priceCents, String idempotencyKey);

    Billed cancelAtPeriodEnd(Current current, boolean cancel, String idempotencyKey);

    /** The local fake's renewal of a membership whose period ended (empty for Stripe, which renews by itself). */
    Optional<Billed> renewIfDue(Current current, long priceCents, Instant now);
}
