package ca.northline.orders.application;

import ca.northline.fulfilment.api.CourierLocations;
import ca.northline.fulfilment.api.DeliveryStatuses;
import ca.northline.identity.api.PersonDirectory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Builds {@link CourierProgress} for the customer's goods and food tracking (S-88). */
@Component
@RequiredArgsConstructor
class CourierProgressReader {

    private final DeliveryStatuses statuses;
    private final CourierLocations locations;
    private final PersonDirectory people;

    @Nullable CourierProgress of(String orderId) {
        var status = statuses.of(orderId).orElse(null);
        if (status == null) {
            return null;
        }
        var live = locations.forOrder(orderId).orElse(null);
        var courierUser = live != null && live.courierUserId() != null ? live.courierUserId() : status.courierUserId();
        String name = null;
        if (courierUser != null) {
            var person = people.people(List.of(courierUser)).get(courierUser);
            name = person == null ? null : person.firstName();
        }
        var position = live == null ? null : live.position();
        var delivered = status.state().equals("delivered");
        return new CourierProgress(
                status.state(),
                status.runLabel(),
                name,
                live != null && live.eta() != null ? live.eta() : status.dropoffEta(),
                live != null ? live.stopsBefore() : status.stopsBefore(),
                position == null ? null : position.lat(),
                position == null ? null : position.lng(),
                position == null ? null : position.at(),
                delivered || status.state().equals("cancelled") ? null : status.pin());
    }
}
