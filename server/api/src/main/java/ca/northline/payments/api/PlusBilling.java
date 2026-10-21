package ca.northline.payments.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Northline Plus billing for the loyalty module, which owns the membership (S-152; S-162: billed by Northline itself,
 * no Stripe Billing). Payments owns the money: the plan's RFC 5545 rule and price per membership
 * ({@code payments.plus_schedules}), one period at a time ({@code payments.plus_periods}), each charged once as an
 * automatic-capture PaymentIntent on the payer's saved card (S-59 SetupIntent), tax from the TaxGateway, the ledger
 * ({@code revenue} + {@code tax_payable}, ref {@code plus_invoice}); renewals are charged off-session by the renewal job
 * and their outcome comes back as {@link PlusBillingChanged}. Stripe sees PaymentIntents, SetupIntents and refunds
 * only.
 */
public interface PlusBilling {

    String NO_CARD = "Add a card in Payment methods first: Plus renews on it.";
    String NOT_YOUR_CARD = "Choose one of your saved cards.";
    String DECLINED = "Your bank declined the card. Try another card in Payment methods.";
    String NO_ADDRESS = "Add your home address in Addresses first: Plus is taxed where you live.";
    String NOTHING_TO_CONFIRM = "There's no Plus payment waiting for you.";

    /** The card processor: {@code stripe} or {@code local} (the fake gateway; the {@code loyalty.memberships.provider}). */
    String provider();

    /** How long a refused renewal is retried ({@code PLUS_RETRY_DAYS}' last): benefits must last at least this long. */
    Duration retryWindow();

    /**
     * A plan as configured (loyalty's {@code northline.plus.*}).
     *
     * @param plan {@code monthly | annual}
     * @param rrule the plan's RFC 5545 rule ({@code FREQ=MONTHLY;INTERVAL=1}); anchored to the first paid instant
     * @param priceCents before tax
     */
    record Plan(String plan, String rrule, long priceCents) {

        /** @throws IllegalArgumentException for a rule that isn't {@code FREQ=MONTHLY|YEARLY[;INTERVAL=n]} */
        public Plan {
            rrule = ca.northline.payments.domain.BillingRule.planRule(rrule);
            if (priceCents < 0) {
                throw new IllegalArgumentException("A Plus price is ≥ 0: " + priceCents);
            }
        }
    }

    /**
     * @param trialDays free days before the first charge (0 = charged now)
     * @param province where the payer lives (the tax's place of supply)
     * @param clientKey the request's Idempotency-Key: a retried join makes the same first charge
     */
    record Join(String userId, String householdId, Plan plan, int trialDays, String province, String clientKey) {}

    /**
     * The membership's billing as it stands.
     *
     * @param subscriptionId the billing id ({@code plus_<ULID>})
     * @param status {@code incomplete | trialing | active | past_due | canceled}
     * @param clientSecret a payment that needs the member (3-D Secure) — confirm it with Stripe.js / the app's SDK
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

    /** Starts the membership: the trial, or the first period charged now (409 {@code card_declined} when refused). */
    Billed subscribe(Join join);

    /** Monthly ↔ annual from the next renewal (back to the current plan = no change waiting). */
    Billed changePlan(String subscriptionId, Plan next);

    /** No renewal after the current period (true), or renew again (false). */
    Billed cancelAtPeriodEnd(String subscriptionId, boolean cancel);

    /**
     * The card Plus renews on: one of the payer's saved cards (422 {@link #NOT_YOUR_CARD}), or null for their default
     * card. A payment being retried is tried again on the next run.
     */
    Billed useCard(String subscriptionId, String payerId, @Nullable String paymentMethod);

    /** After the member confirmed the waiting payment with their bank: reads the PaymentIntent again. */
    Billed confirm(String subscriptionId);

    /**
     * An S-152 membership (Stripe Billing or the local fake, {@code sub_…}) moves to Northline's billing: its next
     * period starts at {@code periodEnd} on the plan's rule and price.
     */
    Billed adopt(String payerId, String householdId, Plan plan, Instant periodEnd, String province, boolean trialing);

    /**
     * What the account shows about the billing.
     *
     * @param nextPlan the plan from the next renewal, when a change waits
     * @param nextChargeAt the next renewal's date; null when it won't renew
     * @param nextChargeCents its price before tax
     * @param pending a payment that failed or waits for the member
     * @param periods the latest periods, newest first
     */
    record Account(
            String subscriptionId,
            @Nullable String nextPlan,
            @Nullable Instant nextChargeAt,
            long nextChargeCents,
            @Nullable Card card,
            @Nullable Pending pending,
            List<Period> periods) {

        public Account {
            periods = List.copyOf(periods);
        }
    }

    /** @param chosen chosen for Plus (false = the default card is used) */
    record Card(String id, String brand, String last4, boolean chosen) {}

    /**
     * @param reason {@code authentication_required} (confirm with the bank) or the decline code
     * @param retryAt the next automatic try; null when none is left
     * @param lapsesAt when the membership ends if nothing is paid
     */
    record Pending(
            String periodId,
            String reason,
            long totalCents,
            @Nullable Instant retryAt,
            Instant lapsesAt,
            @Nullable String clientSecret,
            @Nullable String publishableKey) {}

    /** @param state {@code free | scheduled | retrying | requires_action | paid | lapsed | void | refunded} */
    record Period(
            String id,
            Instant startsAt,
            Instant endsAt,
            String plan,
            String state,
            long amountCents,
            long taxCents,
            @Nullable Instant paidAt) {}

    Optional<Account> account(String subscriptionId);
}
