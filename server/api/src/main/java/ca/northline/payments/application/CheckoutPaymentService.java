package ca.northline.payments.application;

import ca.northline.payments.api.PaymentAuthorizations;
import ca.northline.payments.application.PaymentGateway.IntentStatus;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens the manual-capture PaymentIntent behind an escrow hold: one per job or order line, on the platform account,
 * CAD, in the order's / booking's transfer group, the card saved to the customer's Stripe Customer for off-session
 * re-authorization. The Stripe Customer carries only our user id.
 */
@Service
@RequiredArgsConstructor
@Transactional
class CheckoutPaymentService implements PaymentAuthorizations {

    private final EscrowRepository escrows;
    private final PaymentGateway gateway;
    private final Clock clock;

    @Override
    public Started start(Request request) {
        if (request.amountCents() <= 0 || request.taxCents() < 0) {
            throw RuleViolation.of("amountCents", "range", "Enter an amount.");
        }
        var total = request.amountCents() + request.taxCents();
        var customer = escrows.stripeCustomer(request.customerId()).orElseGet(() -> {
            var created =
                    gateway.customer(request.customerId(), StripeIdempotencyKeys.of("customer", request.customerId()));
            escrows.saveStripeCustomer(request.customerId(), created);
            return created;
        });
        var clientKey = request.clientKey();
        var key = clientKey == null
                ? StripeIdempotencyKeys.of("authorize", request.refType(), request.refId())
                : StripeIdempotencyKeys.fromClient("authorize", request.refType() + ":" + request.refId(), clientKey);
        var authorization = gateway.authorize(new PaymentGateway.Authorize(
                total,
                request.transferGroup(),
                customer,
                null,
                false,
                StripeMetadata.reference(request.merchantId(), request.refType(), request.refId()),
                key));
        var authorized = authorization.status() == IntentStatus.AUTHORIZED;
        escrows.recordPaymentIntent(new EscrowRepository.IntentRecord(
                authorization.paymentIntent(),
                authorization.status(),
                request.customerId(),
                total,
                customer,
                authorization.paymentMethod(),
                authorization.charge(),
                request.transferGroup(),
                request.refType(),
                request.refId(),
                request.merchantId(),
                authorized ? clock.instant() : null,
                authorization.captureBefore(),
                0));
        return new Started(
                authorization.paymentIntent(),
                authorization.clientSecret(),
                authorization.status().code());
    }
}
