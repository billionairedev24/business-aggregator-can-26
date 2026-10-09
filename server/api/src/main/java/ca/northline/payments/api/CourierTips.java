package ca.northline.payments.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Courier tips (mobile gaps part 2; S-57 food tips). 100 % goes to the courier: a tip is owed to them in the ledger
 * ({@code courier:<user id>}; until the delivery names the courier, the pooled {@code courier_tips}). Couriers aren't
 * paid out by Northline yet (S-89), so the ledger is the record of what each is owed. A tip is not a taxable supply:
 * no GST/HST/PST/QST is charged on it. Refunds only in the defined cases ({@link #refund}).
 */
public interface CourierTips {

    /** Food tips are taken with the order (escrow platform charges), so up to $100 or 30 % of the order (S-57). */
    long MAX_CENTS = 10_000;

    int MAX_PERCENT = 30;

    String TOO_MUCH = "Choose a tip between $0 and $100, or up to 30 %.";
    String TOO_SMALL = "Tips start at $1.00.";
    String NOT_DELIVERED = "You can tip once the courier has delivered.";
    String NO_COURIER = "This order wasn't brought by a courier.";
    String ALREADY = "You've already tipped for this delivery.";
    String WINDOW = "Tips can be added up to 7 days after the delivery.";
    String NOT_CHARGED = "The tip hasn't been charged yet.";
    String REASON = "Choose why the tip is refunded.";
    String REFUNDED = "This tip was already refunded.";

    /**
     * @param source {@code checkout} | {@code after_delivery}
     * @param state {@code pending} | {@code captured} | {@code allocated} (owed to the courier) | {@code refunded} |
     *     {@code canceled}
     */
    record Tip(
            String id,
            String orderId,
            long amountCents,
            String source,
            String state,
            @Nullable String courierUserId,
            Instant createdAt,
            @Nullable String clientSecret,
            @Nullable String paymentIntent) {}

    /**
     * A tip taken at checkout, on the order's PaymentIntent (the food order's escrow, or the goods order's delivery fee).
     * Idempotent per order.
     */
    void atCheckout(String orderId, String customerId, long cents, String stripePaymentIntent);

    /** The courier delivered the order: their tips move to them. Idempotent. */
    void allocate(String orderId, String courierUserId, Instant at);

    /**
     * A tip after the delivery: its own card payment (Stripe.js / PaymentSheet confirms {@code clientSecret}; the fake
     * gateway authorizes at once). One per order.
     */
    Tip startAfterDelivery(
            String orderId, String customerId, String courierUserId, long cents, @Nullable String clientKey);

    /** The card authorized it: charged and owed to the courier. */
    Tip confirm(String customerId, String tipId);

    List<Tip> ofOrder(String orderId);

    /**
     * Console (finance): refunds a charged tip to the card, only when the delivery didn't happen, the tip was charged
     * twice or for the wrong amount.
     *
     * @param reason {@code not_delivered} | {@code duplicate} | {@code amount_error}
     */
    Tip refund(String tipId, String reason, String staffId, String role);
}
