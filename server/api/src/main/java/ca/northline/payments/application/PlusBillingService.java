package ca.northline.payments.application;

import ca.northline.payments.api.PaymentSettings;
import ca.northline.payments.api.PlusBilling;
import ca.northline.payments.api.PlusSubscriptionUpdated;
import ca.northline.payments.domain.LedgerEntry;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PlusBilling} over the {@link MembershipBilling} port, and the Plus side of the Stripe webhooks: subscription
 * changes become {@link PlusSubscriptionUpdated} for the loyalty module (in the processing transaction: the outbox);
 * paid invoices are recorded once ({@code payments.plus_invoices}) and posted to the ledger as revenue and tax.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class PlusBillingService implements PlusBilling {

    private final MembershipBilling billing;
    private final PaymentGateway payments;
    private final EscrowRepository customers;
    private final SavedCardGateway cards;
    private final PaymentSettings settings;
    private final PlusInvoiceStore invoices;
    private final LedgerRepository ledger;
    private final ApplicationEventPublisher events;

    @Override
    public String provider() {
        return billing.provider();
    }

    @Override
    public Billed subscribe(
            String userId, String householdId, String plan, long priceCents, int trialDays, String idempotencyKey) {
        var customer = customers.stripeCustomer(userId).orElse(null);
        var card = customer == null ? null : cards.defaultCard(customer);
        if (customer == null || card == null) {
            if ("stripe".equals(billing.provider())) {
                throw new Conflict("card_required", NO_CARD);
            }
            // the fake bills nothing: a person without a saved card still gets a local membership
            customer = customer == null
                    ? payments.customer(userId, StripeIdempotencyKeys.of("customer", userId))
                    : customer;
            customers.saveStripeCustomer(userId, customer);
        }
        var sub = billing.subscribe(new MembershipBilling.Subscribe(
                userId,
                householdId,
                customer,
                card,
                plan,
                priceCents,
                trialDays,
                StripeIdempotencyKeys.of("plus", householdId, idempotencyKey)));
        record(sub, householdId);
        return billed(sub);
    }

    @Override
    public Billed changePlan(Current current, String plan, long priceCents, String idempotencyKey) {
        var sub = billing.changePlan(
                port(current),
                plan,
                priceCents,
                StripeIdempotencyKeys.of("plus-plan", current.subscriptionId(), idempotencyKey));
        record(sub, current.householdId());
        return billed(sub);
    }

    @Override
    public Billed cancelAtPeriodEnd(Current current, boolean cancel, String idempotencyKey) {
        var sub = billing.cancelAtPeriodEnd(
                port(current),
                cancel,
                StripeIdempotencyKeys.of("plus-cancel", current.subscriptionId(), idempotencyKey));
        return billed(sub);
    }

    @Override
    public Optional<Billed> renewIfDue(Current current, long priceCents, Instant now) {
        return billing.renew(port(current), priceCents, now).map(sub -> {
            record(sub, current.householdId());
            return billed(sub);
        });
    }

    private static MembershipBilling.Current port(Current c) {
        return new MembershipBilling.Current(
                c.subscriptionId(), c.plan(), c.status(), c.currentPeriodEnd(), c.cancelAtPeriodEnd());
    }

    private Billed billed(MembershipBilling.Subscription s) {
        return new Billed(
                s.id(),
                s.status(),
                s.plan(),
                s.currentPeriodEnd(),
                s.cancelAtPeriodEnd(),
                s.clientSecret(),
                s.clientSecret() == null ? null : settings.publishableKey());
    }

    /** An invoice the call paid (the fake's) goes to the books like a webhook's. */
    private void record(MembershipBilling.Subscription s, String householdId) {
        var paid = s.paid();
        if (paid != null) {
            invoicePaid(
                    billing.provider(),
                    paid.id(),
                    s.id(),
                    householdId,
                    s.plan(),
                    paid.amountCents(),
                    paid.taxCents(),
                    paid.paidAt());
        }
    }

    /** @return false when it was recorded before */
    boolean invoicePaid(
            String provider,
            String invoiceId,
            String subscriptionId,
            String householdId,
            String plan,
            long amountCents,
            long taxCents,
            Instant paidAt) {
        var id = Ids.next();
        if (!invoices.insert(
                id, provider, invoiceId, subscriptionId, householdId, plan, amountCents, taxCents, paidAt)) {
            return false;
        }
        if (amountCents > 0) {
            ledger.post(LedgerEntry.plusInvoicePaid(id, amountCents, Math.min(taxCents, amountCents), paidAt));
        }
        return true;
    }

    // ── webhooks (StripeEventProcessor) ─────────────────────────────────────────────────────────────────────────

    /** {@code customer.subscription.created | updated | deleted} of a Plus subscription. */
    boolean subscriptionEvent(StripeEvent event) {
        var o = event.object();
        if (!"plus".equals(o.text("metadata", "northline_kind")) || o.id() == null) {
            return false;
        }
        var status = event.type().equals("customer.subscription.deleted") ? "canceled" : stripeStatus(o.text("status"));
        var items = o.objects("items", "data");
        @Nullable Instant end = items.isEmpty() ? null : items.getFirst().time("current_period_end");
        events.publishEvent(new PlusSubscriptionUpdated(
                Ids.next(),
                event.created(),
                o.id(),
                o.text("metadata", "northline_household"),
                o.text("metadata", "northline_plan"),
                status,
                end,
                o.flag("cancel_at_period_end")));
        return true;
    }

    /** {@code invoice.paid} of a Plus subscription: the revenue. */
    boolean invoicePaidEvent(StripeEvent event) {
        var o = event.object();
        var details = new String[] {"parent", "subscription_details"};
        var kind = o.text(details[0], details[1], "metadata", "northline_kind");
        var subscription = o.text(details[0], details[1], "subscription");
        var household = o.text(details[0], details[1], "metadata", "northline_household");
        if (!"plus".equals(kind) || subscription == null || household == null || o.id() == null) {
            return false;
        }
        long tax = 0;
        for (var t : o.objects("total_taxes")) {
            var amount = t.number("amount");
            tax += amount == null ? 0 : amount;
        }
        var paidAt = o.time("status_transitions", "paid_at");
        var plan = o.text(details[0], details[1], "metadata", "northline_plan");
        var amount = o.number("amount_paid");
        invoicePaid(
                "stripe",
                o.id(),
                subscription,
                household,
                plan == null ? "monthly" : plan,
                amount == null ? 0 : amount,
                tax,
                paidAt == null ? event.created() : paidAt);
        return true;
    }

    static String stripeStatus(@Nullable String stripe) {
        return switch (stripe == null ? "" : stripe) {
            case "trialing" -> "trialing";
            case "active" -> "active";
            case "past_due", "paused" -> "past_due";
            case "incomplete" -> "incomplete";
            default -> "canceled";
        };
    }
}
