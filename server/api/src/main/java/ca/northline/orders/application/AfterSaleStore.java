package ca.northline.orders.application;

import ca.northline.orders.domain.OrderReturn;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-151): an order's cancellations, failed delivery and returns ({@code orders.cancellations},
 * {@code delivery_failures}, {@code returns}, {@code return_lines}) and the order rows they change.
 */
public interface AfterSaleStore {

    /** The order, locked for the rest of the transaction. */
    Optional<OrderFacts> lock(String orderId);

    Optional<OrderFacts> find(String orderId);

    List<LineFacts> lines(String orderId);

    /** The lines are cancelled ({@code restocked}: their units went back to the stock). */
    void cancelLines(Collection<String> lineIds, boolean restocked, Instant at);

    /** S-151 returns: the lines were refunded after the shop got them back ({@code restocked}: back on sale). */
    void refundLines(Collection<String> lineIds, boolean restocked);

    /** The order closes: {@code cancelled} or {@code refunded}. */
    void close(String orderId, String state, Instant at);

    void state(String orderId, String state);

    /** One shop's lines were cancelled: the order may be ready now (every remaining shop packed). */
    void readyIfPacked(String orderId);

    String insertCancellation(Cancellation c);

    List<Cancellation> cancellations(String orderId);

    // ── failed delivery ───────────────────────────────────────────────────────────────────────────────────────────

    Optional<Failure> failure(String orderId);

    /** A failed drop-off: a new row, or the next attempt of a re-delivered order (its choice cleared). */
    void failed(Failure failure);

    void chosen(String orderId, String choice, Instant at, @Nullable String windowId);

    /** Failures nobody chose for by now, oldest first. */
    List<Failure> due(Instant now, int limit);

    // ── returns ───────────────────────────────────────────────────────────────────────────────────────────────────

    String nextReturnRef();

    void insert(OrderReturn r);

    void update(OrderReturn r);

    Optional<OrderReturn> returnById(String returnId);

    /** Locked for the rest of the transaction. */
    Optional<OrderReturn> lockReturn(String returnId);

    List<OrderReturn> returnsOfOrder(String orderId);

    List<OrderReturn> returnsOfMerchant(String merchantId, int limit);

    /** The lines are in this return now ({@code returnId} null: out of any). */
    void lineReturn(Collection<String> lineIds, @Nullable String returnId);

    /**
     * @param type {@code goods} | {@code food}
     * @param deliveryKind {@code pooled} | {@code direct}
     */
    record OrderFacts(
            String id,
            @Nullable String ref,
            String type,
            String state,
            @Nullable String customerId,
            @Nullable String addressId,
            @Nullable String deliveryArea,
            @Nullable String deliveryKind,
            @Nullable String windowId,
            @Nullable Instant deliveredAt) {}

    /** @param state {@code pending} | {@code packed} | {@code short} | {@code refunded} | {@code cancelled} */
    record LineFacts(
            String id,
            String merchantId,
            @Nullable String offerId,
            @Nullable String variantId,
            String title,
            int qty,
            long unitCents,
            String state,
            @Nullable String returnId) {

        public long amountCents() {
            return unitCents * qty;
        }

        public boolean open() {
            return !state.equals("cancelled") && !state.equals("refunded");
        }
    }

    /** @param merchantId the shop whose lines were cancelled; null for the whole order */
    record Cancellation(
            String id,
            String orderId,
            @Nullable String merchantId,
            @Nullable String actorId,
            String actorRole,
            String reason,
            @Nullable String note,
            boolean restock,
            List<String> lineIds,
            boolean whole,
            Instant at) {

        public Cancellation {
            lineIds = List.copyOf(lineIds);
        }
    }

    /** @param choice {@code redeliver} | {@code refund} | {@code auto_refund}, null while the customer hasn't chosen */
    record Failure(
            String orderId,
            String reason,
            Instant failedAt,
            Instant decideBy,
            int attempts,
            @Nullable String choice,
            @Nullable Instant chosenAt,
            long feeCents,
            @Nullable String windowId,
            @Nullable String runId) {}
}
