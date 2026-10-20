package ca.northline.payments.application;

import ca.northline.payments.api.PaymentAuthorizations;
import ca.northline.payments.application.PaymentGateway.Authorization;
import ca.northline.payments.application.PaymentGateway.IntentStatus;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.region.api.Regions;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens the manual-capture PaymentIntent behind an escrow hold: one per job or order line, on the platform account,
 * CAD, in the order's / booking's transfer group, the card saved to the customer's Stripe Customer for off-session
 * re-authorization. The Stripe Customer carries only our user id.
 *
 * <p>S-116: Stripe's receipts go out in the buyer's language — French when the request prefers it or when the
 * merchant's place is French-first (region configuration, Loi 96), else English — set on the Customer only when it
 * changes.
 */
@Service
@RequiredArgsConstructor
@Transactional
class CheckoutPaymentService implements PaymentAuthorizations {

    private final EscrowRepository escrows;
    private final PaymentGateway gateway;
    private final TaxCalculationService taxCalculations;
    private final Clock clock;
    private final PaymentMetrics metrics;
    private final MerchantPlaces places;
    private final Regions regions;
    private final PaymentMethods.SavedCardStore cards;

    static final String NOT_YOUR_CARD = "Choose one of your saved cards.";

    @Override
    public Started start(Request request, @Nullable String savedCard) {
        if (savedCard != null && cards.cards(request.customerId()).stream().noneMatch(c -> c.id().equals(savedCard))) {
            throw RuleViolation.of("paymentMethodId", "allowed", NOT_YOUR_CARD);
        }
        if (request.amountCents() < 0
                || request.amountCents() + request.platformCents() <= 0
                || request.taxCents() < 0
                || request.platformCents() < 0
                || request.creditCents() < 0
                || request.creditCents() > request.amountCents() + request.taxCents()) {
            throw RuleViolation.of("amountCents", "range", "Enter an amount.");
        }
        var calculationId = request.taxCalculationId();
        if (calculationId != null) {
            taxCalculations.use(calculationId, request);
        }
        var total = total(request);
        var customer = escrows.stripeCustomer(request.customerId()).orElseGet(() -> {
            var created =
                    gateway.customer(request.customerId(), StripeIdempotencyKeys.of("customer", request.customerId()));
            escrows.saveStripeCustomer(request.customerId(), created);
            return created;
        });
        receiptLanguage(request.customerId(), customer, request.merchantId());
        var clientKey = request.clientKey();
        var key = clientKey == null
                ? StripeIdempotencyKeys.of("authorize", request.refType(), request.refId())
                : StripeIdempotencyKeys.fromClient("authorize", request.refType() + ":" + request.refId(), clientKey);
        var authorization = gateway.authorize(new PaymentGateway.Authorize(
                total,
                request.transferGroup(),
                customer,
                savedCard,
                false,
                StripeMetadata.reference(request.merchantId(), request.refType(), request.refId()),
                key));
        record(request, authorization, customer, total, 0);
        metrics.checkoutStarted(request.refType(), authorization.status().code());
        return new Started(
                authorization.paymentIntent(),
                authorization.clientSecret(),
                authorization.status().code());
    }

    /**
     * The card behind {@code fromPaymentIntent} (saved to the customer's Stripe Customer at the first checkout,
     * {@code setup_future_usage=off_session}), confirmed off-session. A refusal is an answer, not an exception; an
     * attempt that needs the customer is canceled so it never lingers as a hold.
     */
    @Override
    public OffSession startOffSession(Request request, String fromPaymentIntent, int attempt) {
        var from = escrows.intentByStripeId(fromPaymentIntent).orElse(null);
        var customer = from == null ? null : from.stripeCustomer();
        var card = from == null ? null : from.paymentMethod();
        if (from == null
                || customer == null
                || card == null
                || !request.customerId().equals(from.customerId())) {
            return new OffSession(null, IntentStatus.FAILED.code(), "no_saved_card");
        }
        var total = total(request);
        Authorization authorization;
        try {
            authorization = gateway.authorize(new PaymentGateway.Authorize(
                    total,
                    request.transferGroup(),
                    customer,
                    card,
                    true,
                    StripeMetadata.reference(request.merchantId(), request.refType(), request.refId()),
                    StripeIdempotencyKeys.of(
                            "authorize-off-session", request.refType(), request.refId(), String.valueOf(attempt))));
        } catch (RuntimeException e) { // a decline: Stripe answers 402 card_error for an off-session confirmation
            metrics.checkoutStarted(request.refType(), IntentStatus.FAILED.code());
            return new OffSession(null, IntentStatus.FAILED.code(), "declined");
        }
        var recorded = record(request, authorization, customer, total, 0);
        metrics.checkoutStarted(request.refType(), authorization.status().code());
        if (authorization.status() == IntentStatus.REQUIRES_ACTION) {
            gateway.cancel(
                    authorization.paymentIntent(),
                    StripeIdempotencyKeys.of("cancel-off-session", authorization.paymentIntent()));
            escrows.markPaymentIntent(recorded, IntentStatus.CANCELED);
            return new OffSession(
                    authorization.paymentIntent(), IntentStatus.REQUIRES_ACTION.code(), "authentication_required");
        }
        return new OffSession(
                authorization.paymentIntent(),
                authorization.status().code(),
                authorization.status() == IntentStatus.AUTHORIZED ? null : "declined");
    }

    @Override
    public java.util.Optional<String> latest(String refType, String refId) {
        return escrows.currentIntentForUpdate(refType, refId).map(EscrowRepository.Intent::stripePaymentIntent);
    }

    private static long total(Request request) {
        return request.amountCents() + request.taxCents() + request.platformCents() - request.creditCents();
    }

    /** @return our row id */
    private String record(Request request, Authorization authorization, String customer, long total, int reauths) {
        var authorized = authorization.status() == IntentStatus.AUTHORIZED;
        return escrows.recordPaymentIntent(new EscrowRepository.IntentRecord(
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
                reauths));
    }

    /** The receipts' language: the merchant's place's rule over the request's language (LanguageRules). */
    private void receiptLanguage(String customerId, String stripeCustomer, String merchantId) {
        var place = places.of(merchantId);
        var rules = regions.languageRules(place.province(), place.marketId() != null ? place.marketId() : place.city());
        var language = rules.language(null, LocaleContextHolder.getLocale());
        var tag = language.getLanguage().equals("fr") ? "fr-CA" : "en-CA";
        if (!escrows.receiptLocale(customerId).map(tag::equals).orElse(false)) {
            gateway.receiptLocale(stripeCustomer, tag);
            escrows.saveReceiptLocale(customerId, tag);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean authorized(String paymentIntent, long amountCents) {
        var authorization = gateway.authorization(paymentIntent);
        return authorization.status() == IntentStatus.AUTHORIZED
                && (authorization.amountCapturableCents() == 0 || authorization.amountCapturableCents() >= amountCents);
    }

    @Override
    public void cancel(String paymentIntent) {
        var authorization = gateway.authorization(paymentIntent);
        if (authorization.status() == IntentStatus.AUTHORIZED
                || authorization.status() == IntentStatus.REQUIRES_ACTION) {
            gateway.cancel(paymentIntent, StripeIdempotencyKeys.of("cancel-checkout", paymentIntent));
        }
    }
}
