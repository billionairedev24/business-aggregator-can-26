package ca.northline.orders.domain;

import ca.northline.orders.api.OrderPacked;
import ca.northline.orders.domain.OrderEnums.LineState;
import ca.northline.orders.domain.OrderEnums.OrderState;
import ca.northline.orders.domain.OrderEnums.SellerStatus;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Singular;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * One merchant's share of a (possibly multi-merchant) order: its lines plus what it needs to know about the others.
 * "Mark packed" packs all of this merchant's pending lines; the order becomes {@code ready} when no merchant has a
 * pending line left, otherwise {@code packing}.
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class MerchantOrder {

    private static final Set<OrderState> PACKABLE = Set.of(OrderState.PLACED, OrderState.ACCEPTED, OrderState.PACKING);

    @EqualsAndHashCode.Include
    @ToString.Include
    private final String orderId;

    private final String merchantId;

    @ToString.Include
    private OrderState orderState;

    @Singular
    private List<Line> lines;

    /** Other merchants on the same order still have lines to pack. */
    private final boolean othersPending;

    public record Line(String id, LineState state, @Nullable String issueNote) {}

    public SellerStatus status() {
        if (orderState == OrderState.CANCELLED) {
            return SellerStatus.CANCELLED;
        }
        if (orderState == OrderState.REFUNDED
                || lines.stream()
                        .anyMatch(l -> l.state() == LineState.SHORT
                                || l.state() == LineState.REFUNDED
                                || l.issueNote() != null)) {
            return SellerStatus.ISSUE;
        }
        if (orderState == OrderState.DELIVERED || orderState == OrderState.CONFIRMED) {
            return SellerStatus.DELIVERED;
        }
        if (orderState == OrderState.PICKED_UP) {
            return SellerStatus.OUT_FOR_DELIVERY;
        }
        return lines.stream().anyMatch(l -> l.state() == LineState.PENDING)
                ? SellerStatus.TO_PACK
                : SellerStatus.AWAITING_PICKUP;
    }

    /** "Mark packed". 409 {@code order_state} unless the order is still being prepared and has lines to pack. */
    public OrderPacked pack(String actorId, Instant at) {
        var pending = lines.stream().filter(l -> l.state() == LineState.PENDING).toList();
        if (!PACKABLE.contains(orderState) || pending.isEmpty()) {
            throw new Conflict("order_state", "This order has nothing left to pack.");
        }
        lines = lines.stream()
                .map(l -> l.state() == LineState.PENDING ? new Line(l.id(), LineState.PACKED, l.issueNote()) : l)
                .toList();
        orderState = othersPending ? OrderState.PACKING : OrderState.READY;
        return new OrderPacked(Ids.next(), at, orderId, merchantId, actorId, pending.size(), orderState.code());
    }
}
