package ca.northline.food.api;

import ca.northline.platform.EventType;
import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code food.item_availability} — an item was sold out for the day or came back ("Available" / "Sold out today"), or
 * its customer visibility changed (published, approved, hidden, deleted). Search updates or drops the dish. Topic
 * {@code food.menu}, key = item id.
 *
 * @param visible the customer can order it now (published, approved, menu live, not sold out)
 * @param soldOutOn the kitchen's local date it is sold out for, when sold out
 */
@EventType("food.item_availability")
@Externalized("food.menu::#{aggregateId()}")
public record MenuItemAvailabilityChanged(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String menuId,
        boolean visible,
        @Nullable String soldOutOn)
        implements DomainEvent {}
