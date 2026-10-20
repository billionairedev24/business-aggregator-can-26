package ca.northline.payments.application;

import ca.northline.payments.application.PaymentGateway.IntentStatus;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-153, the hold-expiry sweeper (S-55's follow-up): a booking's PaymentIntent whose card was confirmed but which never
 * became escrow — the 10-minute slot hold lapsed before "confirm", the customer left a quote acceptance, a balance
 * attempt was left behind — is canceled at Stripe once {@code grace} has passed since it was created, so the customer's
 * card is freed at once instead of after Stripe's 7 days. Only intents no escrow points to; renewed (replaced) ones are
 * the renewal's business. {@code northline.payments.unheld-booking-grace} ({@code PAYMENTS_UNHELD_BOOKING_GRACE},
 * default 30 minutes: well past the slot hold, so a customer still confirming is never cut off).
 */
@Slf4j
@Service
@Transactional
class UnheldPaymentSweeper {

    static final int BATCH = 100;

    private final EscrowRepository escrows;
    private final PaymentGateway gateway;
    private final Clock clock;
    private final Duration grace;

    UnheldPaymentSweeper(
            EscrowRepository escrows,
            PaymentGateway gateway,
            Clock clock,
            @Value("${northline.payments.unheld-booking-grace:PT30M}") Duration grace) {
        this.escrows = escrows;
        this.gateway = gateway;
        this.clock = clock;
        this.grace = grace;
    }

    /** @return how many PaymentIntents were canceled */
    int sweep() {
        var swept = 0;
        for (var intent : escrows.unheldBookingIntents(clock.instant().minus(grace), BATCH)) {
            var now = gateway.authorization(intent.stripePaymentIntent());
            if (now.status() == IntentStatus.AUTHORIZED || now.status() == IntentStatus.REQUIRES_ACTION) {
                gateway.cancel(intent.stripePaymentIntent(), StripeIdempotencyKeys.of("cancel-unheld", intent.id()));
                escrows.markPaymentIntent(intent.id(), IntentStatus.CANCELED);
                log.info(
                        "Booking payment {} ({} {}) never became escrow; canceled at Stripe",
                        intent.stripePaymentIntent(),
                        intent.refType(),
                        intent.refId());
                swept++;
            } else {
                escrows.markPaymentIntent(intent.id(), now.status()); // Stripe moved on (canceled, failed) — follow it
            }
        }
        return swept;
    }
}
