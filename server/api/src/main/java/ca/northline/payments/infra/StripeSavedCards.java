package ca.northline.payments.infra;

import ca.northline.payments.application.SavedCardGateway;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentMethod;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerPaymentMethodListParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.PaymentMethodDetachParams;
import com.stripe.param.SetupIntentCreateParams;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Saved cards at Stripe (stripe-java; S-59): card-only, {@code usage=off_session} SetupIntents on the platform
 * account's Customer, the Customer's card PaymentMethods, {@code invoice_settings.default_payment_method} as "Default",
 * detach to remove. Never run against the live API in this repository — tested against stripe-mock
 * ({@code StripeSavedCardsMockTest}).
 */
class StripeSavedCards implements SavedCardGateway {

    private final StripeClient stripe;

    StripeSavedCards(StripeClient stripe) {
        this.stripe = stripe;
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
    public SetupIntent createSetupIntent(String stripeCustomer, String idempotencyKey) {
        var params = SetupIntentCreateParams.builder()
                .setCustomer(stripeCustomer)
                .addPaymentMethodType("card")
                .setUsage(SetupIntentCreateParams.Usage.OFF_SESSION)
                .putMetadata("northline_purpose", "saved_card")
                .build();
        return call("setup intent", () -> of(stripe.v1().setupIntents().create(params, key(idempotencyKey))));
    }

    @Override
    public SetupIntent setupIntent(String setupIntentId) {
        return call("setup intent", () -> of(stripe.v1().setupIntents().retrieve(setupIntentId)));
    }

    private static SetupIntent of(com.stripe.model.SetupIntent s) {
        return new SetupIntent(s.getId(), s.getStatus(), s.getClientSecret(), s.getCustomer(), s.getPaymentMethod());
    }

    @Override
    public List<Card> cards(String stripeCustomer) {
        var params = CustomerPaymentMethodListParams.builder()
                .setType(CustomerPaymentMethodListParams.Type.CARD)
                .setLimit(20L)
                .build();
        return call(
                "payment methods",
                () -> stripe.v1().customers().paymentMethods().list(stripeCustomer, params).getData().stream()
                        .filter(pm -> pm.getCard() != null)
                        .map(StripeSavedCards::card)
                        .toList());
    }

    private static Card card(PaymentMethod pm) {
        var c = Objects.requireNonNull(pm.getCard());
        return new Card(
                pm.getId(),
                Objects.requireNonNullElse(c.getBrand(), "card"),
                Objects.requireNonNullElse(c.getLast4(), "····"),
                c.getExpMonth() == null ? 0 : c.getExpMonth().intValue(),
                c.getExpYear() == null ? 0 : c.getExpYear().intValue(),
                Instant.ofEpochSecond(pm.getCreated() == null ? 0 : pm.getCreated()));
    }

    @Override
    public @Nullable String defaultCard(String stripeCustomer) {
        var customer = call("customer", () -> stripe.v1().customers().retrieve(stripeCustomer));
        var settings = customer.getInvoiceSettings();
        return settings == null ? null : settings.getDefaultPaymentMethod();
    }

    @Override
    public void makeDefault(String stripeCustomer, String paymentMethod, String idempotencyKey) {
        var params = CustomerUpdateParams.builder()
                .setInvoiceSettings(CustomerUpdateParams.InvoiceSettings.builder()
                        .setDefaultPaymentMethod(paymentMethod)
                        .build())
                .build();
        call("default card", () -> stripe.v1().customers().update(stripeCustomer, params, key(idempotencyKey)));
    }

    @Override
    public void detach(String paymentMethod, String idempotencyKey) {
        call(
                "detach",
                () -> stripe.v1()
                        .paymentMethods()
                        .detach(
                                paymentMethod,
                                PaymentMethodDetachParams.builder().build(),
                                key(idempotencyKey)));
    }
}
