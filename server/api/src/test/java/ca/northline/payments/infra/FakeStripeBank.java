package ca.northline.payments.infra;

import ca.northline.payments.application.PaymentGateway;

/**
 * S-162 tests: what the member does at their bank — the fake gateway's PaymentIntent that needed 3-D Secure succeeds,
 * as after Stripe.js's {@code confirmCardPayment} or the app's {@code handleNextAction}.
 */
public final class FakeStripeBank {

    private FakeStripeBank() {}

    public static void authenticate(PaymentGateway gateway, String paymentIntent) {
        ((FakeStripeGateway) gateway).authenticate(paymentIntent);
    }
}
