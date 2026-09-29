package ca.northline.food.domain;

import ca.northline.food.api.FoodOrderHandedOff;
import ca.northline.food.api.KitchenOrderAccepted;
import ca.northline.food.api.KitchenOrderReady;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * The kitchen's ticket for one food order (Live orders): Accept · start cooking → Mark ready → Handed to courier /
 * customer. Each step returns the event the orders module (order state) and payments (escrow on handoff) react to.
 * A cancelled or refunded order can't move (409 {@code order_closed}); a step out of order is 409 {@code kitchen_stage}.
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class KitchenTicket {

    @EqualsAndHashCode.Include
    @ToString.Include
    private final String orderId;

    private final String merchantId;

    /** delivery | pickup. */
    private final String fulfilmentMode;

    /** The order is still open (not cancelled / refunded). */
    private final boolean orderOpen;

    @ToString.Include
    private KitchenStage stage;

    private @Nullable Integer prepMin;
    private @Nullable Instant acceptedAt;
    private @Nullable String acceptedBy;
    private @Nullable Instant readyBy;
    private @Nullable Instant readyAt;
    private @Nullable Instant handedOffAt;
    private @Nullable String handedOffBy;

    public KitchenOrderAccepted accept(String actorId, Instant at, int promisedMin) {
        require(KitchenStage.NEW);
        stage = KitchenStage.COOKING;
        prepMin = promisedMin;
        acceptedAt = at;
        acceptedBy = actorId;
        readyBy = at.plus(Duration.ofMinutes(promisedMin));
        return new KitchenOrderAccepted(Ids.next(), at, orderId, merchantId, actorId, promisedMin, readyBy);
    }

    public KitchenOrderReady ready(String actorId, Instant at) {
        require(KitchenStage.COOKING);
        stage = KitchenStage.READY;
        readyAt = at;
        return new KitchenOrderReady(
                Ids.next(), at, orderId, merchantId, actorId, at.isAfter(Objects.requireNonNull(readyBy)));
    }

    public FoodOrderHandedOff handOff(String actorId, Instant at) {
        require(KitchenStage.READY);
        stage = KitchenStage.HANDED_OFF;
        handedOffAt = at;
        handedOffBy = actorId;
        return new FoodOrderHandedOff(Ids.next(), at, orderId, merchantId, actorId, fulfilmentMode);
    }

    private void require(KitchenStage expected) {
        if (!orderOpen) {
            throw new Conflict("order_closed", "This order was cancelled.");
        }
        if (stage != expected) {
            throw new Conflict("kitchen_stage", "Someone already moved this order. Refresh to see where it is.");
        }
    }
}
