package ca.northline.orders.application;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The customer's order after checkout (S-52, design 06 {@code confirmed}): what was bought from which shop, the
 * delivery (pooled run or direct courier) and the four steps of the design's timeline, from the order's state and the
 * shops' packing. Only the customer who placed it sees it (404 otherwise).
 */
public interface TrackOrder {

    OrderTracking view(String customerId, String orderId);

    /** Shown on the proof-of-delivery photo endpoint when the order is someone else's. */
    String NOT_YOUR_ORDER = "This order isn't yours.";

    /**
     * The courier's proof-of-delivery photo of the customer's own order as a short-lived signed URL (fulfilment's
     * {@code DeliveryProofPhotos}): 403 for someone else's order, 404 for no such order or no photo to show (PIN or
     * signature, removed by retention, a delivery with an ID check at the door).
     */
    ProofPhoto proofPhoto(String customerId, String orderId);

    /** @param url absolute (object storage) or relative to the api's origin ({@code local}: {@code /api/v1/dev/…}) */
    record ProofPhoto(String url, Instant expiresAt) {}

    /**
     * @param state {@code "placed"} | {@code "accepted"} | {@code "packing"} | {@code "ready"} | {@code "picked_up"} |
     *     {@code "delivered"} | {@code "confirmed"} | {@code "refunded"} | {@code "cancelled"}
     * @param steps paid → packing → pickup → delivered, each {@code done} | {@code current} | {@code todo}
     * @param deliveryProof the courier's proof at drop-off: {@code photo} | {@code signature} | {@code pin} (S-78)
     * @param canConfirm the customer can confirm receipt now ({@link ConfirmDelivery}; S-78)
     * @param paysShopsAt when the shops are paid without a confirmation: 7 days after delivery (goods, S-78)
     * @param courier the courier bringing it, live (S-88); null before fulfilment has the order
     * @param canCancel S-151: the customer may cancel it now (configuration: before any shop packed, by default)
     * @param cancellations S-151: what was cancelled — the whole order, or one shop's lines
     * @param deliveryFailure S-151: the courier couldn't hand it over; the customer chooses by {@code decideBy}
     * @param returnable S-151: lines the customer may still return, with the window's end
     * @param returns S-151: the order's returns
     */
    record OrderTracking(
            String orderId,
            @Nullable String ref,
            String type,
            String state,
            Instant placedAt,
            long subtotalCents,
            long deliveryFeeCents,
            long taxCents,
            long totalCents,
            Delivery delivery,
            List<ShopProgress> shops,
            List<Step> steps,
            @Nullable Instant deliveredAt,
            @Nullable String deliveryProof,
            @Nullable Instant confirmedAt,
            boolean canConfirm,
            @Nullable Instant paysShopsAt,
            @Nullable CourierProgress courier,
            boolean canCancel,
            List<CancellationInfo> cancellations,
            @Nullable FailureInfo deliveryFailure,
            List<ReturnableLine> returnable,
            List<AfterSaleUseCases.ReturnView> returns) {

        public OrderTracking {
            shops = List.copyOf(shops);
            steps = List.copyOf(steps);
            cancellations = List.copyOf(cancellations);
            returnable = List.copyOf(returnable);
            returns = List.copyOf(returns);
        }
    }

    /**
     * @param by {@code customer} | {@code merchant} | {@code staff} | {@code system}
     * @param merchantName the shop whose lines were cancelled; null for the whole order
     */
    record CancellationInfo(
            String by,
            String reason,
            Instant at,
            boolean whole,
            @Nullable String merchantName) {}

    /**
     * @param reason {@code absent} | {@code unsafe} | {@code refused} | {@code access} | {@code other}
     * @param choice {@code redeliver} | {@code refund} | {@code auto_refund}, null until chosen
     * @param feeCents what a refund keeps of the delivery fee
     */
    record FailureInfo(
            String reason,
            Instant failedAt,
            Instant decideBy,
            int attempts,
            @Nullable String choice,
            @Nullable Instant chosenAt,
            long feeCents) {}

    record ReturnableLine(
            String lineId, String merchantId, String title, int qty, long amountCents, Instant returnUntil) {}

    /**
     * @param kind {@code pooled} | {@code direct} | {@code pickup}
     * @param day the run's day in the market's time zone: {@code today} | {@code tomorrow} | {@code later}
     * @param etaAt the direct courier's expected arrival
     */
    record Delivery(
            String kind,
            @Nullable String runLabel,
            @Nullable String day,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            int households,
            @Nullable Instant etaAt) {}

    /** @param cancelled S-151: every line of this shop was cancelled */
    record ShopProgress(String merchantId, String name, int items, boolean packed, boolean cancelled) {}

    /** @param key {@code paid} | {@code packing} | {@code pickup} | {@code delivered} */
    record Step(String key, String state) {}
}
