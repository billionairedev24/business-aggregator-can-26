package ca.northline.payments.application;

import ca.northline.shared.CodedEnum;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: charges on the Northline platform account (Stripe Connect Express, separate charges and transfers).
 * The customer pays Northline with a manual-capture PaymentIntent (the escrow hold); capture happens at fulfilment;
 * the merchant's net is transferred to their connected account at release, in the PaymentIntent's
 * {@code transfer_group}. Implemented by the Stripe adapter when a key is configured, by the local fake otherwise.
 * Every mutating call carries an idempotency key ({@code StripeIdempotencyKeys}) so a retried job can't pay twice.
 * Amounts are CAD cents.
 */
public interface PaymentGateway {

    /** Stripe PaymentIntent status, as {@code payments.payment_intents.state} stores it. */
    enum IntentStatus implements CodedEnum {
        /** Waiting for the customer (card, 3-D Secure, confirmation) or processing. */
        REQUIRES_ACTION,
        /** {@code requires_capture}: the hold. */
        AUTHORIZED,
        /** {@code succeeded}. */
        CAPTURED,
        CANCELED,
        FAILED,
        /** The charge was refunded in full (only from {@code charge.refunded}). */
        REFUNDED
    }

    /**
     * A manual-capture PaymentIntent for one job or order line.
     *
     * @param amountCents what the customer pays: the merchant's amount + tax
     * @param transferGroup {@code order:<orderId>} or {@code booking:<bookingId>} — ties the charge to its transfers
     * @param stripeCustomer {@code cus_…}; the card is saved to it for off-session re-authorization
     * @param paymentMethod {@code pm_…} to confirm server-side (re-authorization), or null for Stripe.js to confirm
     * @param metadata Northline ids only ({@code northline_*}), never names or contact details
     */
    record Authorize(
            long amountCents,
            String transferGroup,
            String stripeCustomer,
            @Nullable String paymentMethod,
            boolean offSession,
            Map<String, String> metadata,
            String idempotencyKey) {

        public Authorize {
            metadata = Map.copyOf(metadata);
        }
    }

    /** A PaymentIntent as Stripe reports it. */
    record Authorization(
            String paymentIntent,
            IntentStatus status,
            long amountCents,
            long amountCapturableCents,
            @Nullable String clientSecret,
            @Nullable String stripeCustomer,
            @Nullable String paymentMethod,
            @Nullable String charge,
            @Nullable Instant captureBefore,
            @Nullable String transferGroup) {}

    /** Money for a released escrow to the merchant's connected account. */
    record Transfer(
            String connectedAccount,
            long amountCents,
            String transferGroup,
            @Nullable String sourceCharge,
            Map<String, String> metadata,
            String idempotencyKey) {

        public Transfer {
            metadata = Map.copyOf(metadata);
        }
    }

    /**
     * S-162: an automatic-capture PaymentIntent for money Northline keeps itself (a Northline Plus period), confirmed at
     * once with a card the customer saved (S-59 SetupIntent, {@code usage=off_session}). No transfer group: nothing is
     * paid out to a business.
     *
     * @param offSession the customer isn't there (a renewal); false = they are (joining), so the bank may ask them
     * @param idempotencyKey one per membership and period: the same period never makes a second PaymentIntent
     */
    record Charge(
            long amountCents,
            String stripeCustomer,
            String paymentMethod,
            boolean offSession,
            Map<String, String> metadata,
            String idempotencyKey) {

        public Charge {
            metadata = Map.copyOf(metadata);
        }
    }

    /** What a {@link Charge} (or another try of its PaymentIntent) came to. */
    sealed interface Charged {

        /** {@code succeeded}: the money is Northline's. */
        record Paid(String paymentIntent, @Nullable String charge) implements Charged {}

        /**
         * The bank wants the customer (3-D Secure; off-session: {@code authentication_required}): Stripe.js / the
         * mobile SDK confirms {@code clientSecret} with the card.
         */
        record ActionRequired(
                String paymentIntent, @Nullable String clientSecret) implements Charged {}

        /** Still with the card network ({@code processing}). */
        record Processing(String paymentIntent) implements Charged {}

        /**
         * Refused (Stripe answers 402 {@code card_error}): {@code code} is the decline code ({@code insufficient_funds},
         * {@code card_declined}, {@code expired_card} …). The PaymentIntent stays and can be tried again.
         */
        record Declined(@Nullable String paymentIntent, String code) implements Charged {}
    }

    /** Creates and confirms an automatic-capture PaymentIntent; a decline is an answer, not an exception. */
    Charged charge(Charge request);

    /** Tries an existing {@link #charge} PaymentIntent again with {@code paymentMethod} (a retry, a new card). */
    Charged chargeAgain(String paymentIntent, String paymentMethod, boolean offSession, String idempotencyKey);

    /** Reads a {@link #charge} PaymentIntent (after the customer confirmed it, or a webhook said it changed). */
    Charged chargeState(String paymentIntent);

    /** A Stripe Customer holding nothing but our user id ({@code cus_…}); cards are attached by Stripe.js. */
    String customer(String customerId, String idempotencyKey);

    /**
     * S-116: the language Stripe writes the Customer's receipts in ({@code preferred_locales}: {@code fr-CA},
     * {@code en-CA}); Stripe uses the first one it supports.
     */
    void receiptLocale(String stripeCustomer, String locale);

    /** Creates (and, with a payment method, confirms) a manual-capture PaymentIntent. */
    Authorization authorize(Authorize request);

    /** Reads a PaymentIntent (status, capturable amount, {@code capture_before}). */
    Authorization authorization(String stripePaymentIntent);

    /** Captures an authorized PaymentIntent; returns the charge id ({@code ch_…}) transfers draw from. */
    @Nullable
    String capture(String stripePaymentIntent, long amountCents, String idempotencyKey);

    /** Releases an uncaptured hold (refund before capture, replaced authorization). */
    void cancel(String stripePaymentIntent, String idempotencyKey);

    /** Transfers the merchant's net to their connected account; returns the transfer id ({@code tr_…}). */
    String transfer(Transfer transfer);

    /** Pulls money back from a transfer (refund of released money); returns the reversal id ({@code trr_…}). */
    String reverseTransfer(
            String stripeTransfer, long amountCents, Map<String, String> metadata, String idempotencyKey);

    /** Refunds a captured charge to the customer's original payment method; returns the refund id ({@code re_…}). */
    String refund(String stripePaymentIntent, long amountCents, Map<String, String> metadata, String idempotencyKey);
}
