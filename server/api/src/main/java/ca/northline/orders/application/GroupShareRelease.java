package ca.northline.orders.application;

import ca.northline.food.api.FoodOrderHandedOff;
import ca.northline.payments.api.EscrowLifecycle;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * S-159, "food on handoff" for a split bill: the kitchen's hand-off releases the host's share like any food order
 * ({@code food.KitchenEscrowRelease}: its id is the order's); the guests' shares are released here. Idempotent
 * ({@link EscrowLifecycle#fulfilledIfHeld}) for the registry's retries.
 */
@Component
@RequiredArgsConstructor
class GroupShareRelease {

    private final GroupOrderStore groups;
    private final EscrowLifecycle escrow;

    @ApplicationModuleListener
    void on(FoodOrderHandedOff event) {
        groups.guestSharesOfOrder(event.aggregateId())
                .forEach(s -> escrow.fulfilledIfHeld(GroupOrderService.REF_TYPE, s.id(), event.occurredAt()));
    }
}
