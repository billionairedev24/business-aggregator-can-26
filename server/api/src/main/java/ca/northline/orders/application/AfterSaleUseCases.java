package ca.northline.orders.application;

import ca.northline.orders.application.TrackOrder.OrderTracking;
import ca.northline.shared.Bytes;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** S-151's inbound ports: cancelling a goods order, a failed delivery's choice, and returns. */
public final class AfterSaleUseCases {
    private AfterSaleUseCases() {}

    /**
     * Cancelling a goods order (configuration says until when, AfterSaleProperties). The effects follow
     * {@code order.cancelled}: card holds voided or charges refunded, the delivery fee's hold released, the delivery
     * off its run; stock is restored in the same transaction.
     */
    public interface CancelOrders {

        /** The customer cancels the whole order — by default only while no shop has packed it. */
        Cancelled byCustomer(String customerId, String orderId, String reason, @Nullable String note);

        /**
         * A shop cancels its own lines (the whole order when nothing else is left on it) — by default until the courier
         * picks them up — with a reason and whether the units go back on sale.
         */
        Cancelled bySeller(
                String merchantId,
                String orderId,
                String actorId,
                String reason,
                @Nullable String note,
                boolean restock);

        /** Northline staff cancel the whole order — by default until it's delivered; audited. */
        Cancelled byStaff(
                String orderId, String staffId, String roles, String reason, @Nullable String note, boolean restock);
    }

    /**
     * @param state the order's state now
     * @param whole the whole order closed
     */
    public record Cancelled(String cancellationId, String orderId, String state, boolean whole, List<String> lineIds) {
        public Cancelled {
            lineIds = List.copyOf(lineIds);
        }
    }

    /** After a failed drop-off: the customer's choice, and the refund when they don't choose in time. */
    public interface FailedDeliveries {

        /** @param choice {@code redeliver} (the next run) | {@code refund} */
        OrderTracking choose(String customerId, String orderId, String choice);

        /** Refunds every failed delivery whose decision window has passed; returns how many. */
        int refundDue();
    }

    /** Returns of delivered goods: the customer's side and the shop's. */
    public interface Returns {

        ReturnView request(String customerId, String orderId, ReturnRequest request);

        ReturnView withdraw(String customerId, String returnId);

        /** A declined return goes to a Northline agent: a dispute on each of its lines (the disputes queue). */
        ReturnView escalate(String customerId, String returnId, String statement);

        List<ReturnView> ofCustomer(String customerId, String orderId);

        List<ReturnView> ofMerchant(String merchantId);

        ReturnView ofMerchant(String merchantId, String returnId);

        /** @param method {@code pickup} (a courier collects it) | {@code dropoff} (the customer brings it) */
        ReturnView approve(String merchantId, String returnId, String actorId, String method, boolean restock);

        ReturnView decline(String merchantId, String returnId, String actorId, String reason);

        /** The shop has it back (a drop-off, or a pickup the shop marks itself): refunded at once. */
        ReturnView received(String merchantId, String returnId, String actorId);

        /** The customer's photo, for the shop. */
        Optional<Photo> photo(String merchantId, String returnId);
    }

    /** The console's view of an order's after-sale (cancellations with staff notes, a failed delivery, returns). */
    public interface OrderAfterSale {

        AfterSaleView of(String orderId);
    }

    /**
     * @param cancellations every cancellation, with the canceller's note (staff and shops' words: console only)
     */
    public record AfterSaleView(
            String orderId,
            String state,
            List<CancellationRecord> cancellations,
            TrackOrder.@Nullable FailureInfo deliveryFailure,
            List<ReturnView> returns) {

        public AfterSaleView {
            cancellations = List.copyOf(cancellations);
            returns = List.copyOf(returns);
        }
    }

    public record CancellationRecord(
            String id,
            @Nullable String merchantId,
            @Nullable String merchantName,
            String by,
            @Nullable String actorId,
            String reason,
            @Nullable String note,
            boolean restock,
            boolean whole,
            int lines,
            Instant at) {}

    /** @param photo the item, JPG / PNG / WebP ≤ 5 MB */
    public record ReturnRequest(
            List<String> lineIds,
            String reason,
            @Nullable String note,
            @Nullable Bytes photo) {
        public ReturnRequest {
            lineIds = List.copyOf(lineIds);
        }
    }

    public record Photo(Bytes bytes, String contentType) {}

    /**
     * A return as the customer and the shop see it.
     *
     * @param note the customer's own words (shown to them and the shop only)
     * @param amountCents the lines' price before tax; its tax is refunded with it
     */
    public record ReturnView(
            String id,
            String ref,
            String orderId,
            @Nullable String orderRef,
            String merchantId,
            @Nullable String merchantName,
            String state,
            String reason,
            @Nullable String note,
            boolean hasPhoto,
            @Nullable String method,
            boolean restock,
            @Nullable String declineReason,
            long amountCents,
            List<ReturnLine> lines,
            Instant createdAt,
            @Nullable Instant decidedAt,
            @Nullable Instant collectedAt,
            @Nullable Instant receivedAt,
            @Nullable Instant refundedAt,
            boolean canWithdraw,
            boolean canEscalate) {

        public ReturnView {
            lines = List.copyOf(lines);
        }
    }

    public record ReturnLine(String lineId, String title, int qty, long amountCents) {}
}
