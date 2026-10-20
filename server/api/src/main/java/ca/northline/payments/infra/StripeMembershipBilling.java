package ca.northline.payments.infra;

import ca.northline.payments.application.MembershipBilling;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.net.RequestOptions;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Northline Plus on Stripe Billing (stripe-java; S-152): one subscription per household on the payer's platform
 * Customer, the plan's recurring Price ({@code STRIPE_PLUS_MONTHLY_PRICE} / {@code _ANNUAL_PRICE}), billed to their
 * default saved card, {@code payment_behavior=default_incomplete} so a payment that needs 3-D Secure comes back with the
 * invoice's confirmation secret instead of failing; {@code metadata.northline_household} / {@code northline_plan} let
 * the webhooks find the membership. Plan changes prorate; cancelling is at the period's end. Renewals, failed payments
 * and the end of a cancelled subscription arrive by webhook. Never run against the live API in this repository —
 * tested against stripe-mock ({@code StripeMembershipBillingStripeMockTest}).
 */
class StripeMembershipBilling implements MembershipBilling {

    private final StripeClient stripe;
    private final PlusBillingProperties props;

    StripeMembershipBilling(StripeClient stripe, PlusBillingProperties props) {
        this.stripe = stripe;
        this.props = props;
    }

    @FunctionalInterface
    private interface StripeCall<T> {
        T run() throws StripeException;
    }

    private static <T> T call(String what, StripeCall<T> call) {
        try {
            return call.run();
        } catch (StripeException e) {
            throw new IllegalStateException("Stripe " + what + " failed: " + e.getMessage(), e);
        }
    }

    private static RequestOptions key(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    @Override
    public String provider() {
        return "stripe";
    }

    @Override
    public Subscription subscribe(Subscribe r) {
        var params = SubscriptionCreateParams.builder()
                .setCustomer(r.stripeCustomer())
                .addItem(SubscriptionCreateParams.Item.builder()
                        .setPrice(props.price(r.plan()))
                        .build())
                .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
                .setPaymentSettings(SubscriptionCreateParams.PaymentSettings.builder()
                        .setSaveDefaultPaymentMethod(
                                SubscriptionCreateParams.PaymentSettings.SaveDefaultPaymentMethod.ON_SUBSCRIPTION)
                        .build())
                .putMetadata("northline_kind", "plus")
                .putMetadata("northline_household", r.householdId())
                .putMetadata("northline_plan", r.plan())
                .putMetadata("northline_user", r.userId())
                .addExpand("latest_invoice.confirmation_secret");
        if (r.paymentMethod() != null) {
            params.setDefaultPaymentMethod(r.paymentMethod());
        }
        if (r.trialDays() > 0) {
            params.setTrialPeriodDays((long) r.trialDays());
        }
        if (props.automaticTax()) {
            params.setAutomaticTax(SubscriptionCreateParams.AutomaticTax.builder()
                    .setEnabled(true)
                    .build());
        }
        var built = params.build();
        return call("subscription", () -> of(stripe.v1().subscriptions().create(built, key(r.idempotencyKey()))));
    }

    @Override
    public Subscription changePlan(Current c, String plan, long priceCents, String idempotencyKey) {
        var subscriptionId = c.subscriptionId();
        var current = call("subscription", () -> stripe.v1().subscriptions().retrieve(subscriptionId));
        var item = current.getItems() == null || current.getItems().getData().isEmpty()
                ? null
                : current.getItems().getData().getFirst().getId();
        var params = SubscriptionUpdateParams.builder()
                .addItem(SubscriptionUpdateParams.Item.builder()
                        .setId(Objects.requireNonNull(item, "subscription without an item"))
                        .setPrice(props.price(plan))
                        .build())
                .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS)
                .putMetadata("northline_plan", plan)
                .build();
        return call(
                "plan change",
                () -> of(stripe.v1().subscriptions().update(subscriptionId, params, key(idempotencyKey))));
    }

    @Override
    public Subscription cancelAtPeriodEnd(Current c, boolean cancel, String idempotencyKey) {
        var subscriptionId = c.subscriptionId();
        var params =
                SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(cancel).build();
        return call(
                "cancel", () -> of(stripe.v1().subscriptions().update(subscriptionId, params, key(idempotencyKey))));
    }

    @Override
    public Optional<Subscription> renew(Current current, long priceCents, Instant now) {
        return Optional.empty(); // Stripe renews by itself (invoice.paid / customer.subscription.updated)
    }

    private static MembershipBilling.Subscription of(com.stripe.model.Subscription s) {
        var items = s.getItems() == null
                ? java.util.List.<com.stripe.model.SubscriptionItem>of()
                : s.getItems().getData();
        var end = items.isEmpty() || items.getFirst().getCurrentPeriodEnd() == null
                ? null
                : Instant.ofEpochSecond(items.getFirst().getCurrentPeriodEnd());
        var metadata = s.getMetadata();
        var plan = metadata == null ? null : metadata.get("northline_plan");
        @Nullable String secret = null;
        var invoice = s.getLatestInvoiceObject();
        if (invoice != null && invoice.getConfirmationSecret() != null && "incomplete".equals(s.getStatus())) {
            secret = invoice.getConfirmationSecret().getClientSecret();
        }
        return new MembershipBilling.Subscription(
                s.getId(),
                status(s.getStatus()),
                plan == null ? "monthly" : plan,
                end,
                Boolean.TRUE.equals(s.getCancelAtPeriodEnd()),
                secret,
                null);
    }

    /** Stripe's subscription statuses as the membership keeps them. */
    static String status(@Nullable String stripe) {
        return switch (stripe == null ? "" : stripe) {
            case "trialing" -> "trialing";
            case "active" -> "active";
            case "past_due", "paused" -> "past_due";
            case "incomplete" -> "incomplete";
            default -> "canceled"; // canceled, unpaid, incomplete_expired
        };
    }
}
