package ca.northline.orders.application;

import ca.northline.fulfilment.api.DeliveryStatuses;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.orders.api.CustomerOrders;
import ca.northline.payments.api.CourierTips;
import ca.northline.payments.api.CourierTips.Tip;
import ca.northline.payments.api.PaymentSettings;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link TipCourier}: who delivered comes from fulfilment; the money from payments ({@link CourierTips}). */
@Service
@RequiredArgsConstructor
@Transactional
class TipCourierService implements TipCourier {

    static final Duration WINDOW = Duration.ofDays(7);
    static final Set<String> DELIVERED = Set.of("delivered", "confirmed");

    private final CustomerOrders orders;
    private final DeliveryStatuses deliveries;
    private final CourierTips tips;
    private final PaymentSettings settings;
    private final PersonDirectory people;
    private final Clock clock;

    private record Delivered(CustomerOrders.OrderDetail order, String courierUserId) {}

    @Override
    public Started start(String customerId, String orderId, String kind, long value, @Nullable String clientKey) {
        var d = delivered(customerId, orderId);
        var subtotal = d.order().lines().stream()
                .mapToLong(CustomerOrders.Line::amountCents)
                .sum();
        long cents = switch (kind) {
            case "amount" -> value;
            case "percent" -> {
                if (value < 1 || value > CourierTips.MAX_PERCENT) {
                    throw RuleViolation.of("value", "range", CourierTips.TOO_MUCH);
                }
                yield Math.round(subtotal * value / 100.0);
            }
            default -> throw RuleViolation.of("kind", "required", CourierTips.TOO_MUCH);
        };
        if (cents > CourierTips.MAX_CENTS) {
            throw RuleViolation.of("value", "range", CourierTips.TOO_MUCH);
        }
        if (cents < 100) {
            throw RuleViolation.of("value", "range", CourierTips.TOO_SMALL);
        }
        var tip = tips.startAfterDelivery(orderId, customerId, d.courierUserId(), cents, clientKey);
        return new Started(tip, settings.provider(), settings.publishableKey());
    }

    @Override
    public Tip confirm(String customerId, String orderId, String tipId) {
        if (orders.detail(customerId, orderId).isEmpty()) {
            throw new NotFound("order", orderId);
        }
        if (tips.ofOrder(orderId).stream().noneMatch(t -> t.id().equals(tipId))) {
            throw new NotFound("tip", tipId);
        }
        return tips.confirm(customerId, tipId);
    }

    @Override
    @Transactional(readOnly = true)
    public Tips tips(String customerId, String orderId) {
        var order = orders.detail(customerId, orderId).orElseThrow(() -> new NotFound("order", orderId));
        var all = tips.ofOrder(orderId).stream()
                .filter(t -> !"canceled".equals(t.state()))
                .toList();
        var courier = courier(order);
        var canTip = courier != null
                && eligible(order)
                && all.stream().noneMatch(t -> "after_delivery".equals(t.source()) && !"pending".equals(t.state()));
        var name = courier == null
                ? null
                : people.people(List.of(courier)).values().stream()
                        .findFirst()
                        .map(PersonDirectory.Person::firstName)
                        .orElse(null);
        return new Tips(all, canTip, name);
    }

    private Delivered delivered(String customerId, String orderId) {
        var order = orders.detail(customerId, orderId).orElseThrow(() -> new NotFound("order", orderId));
        if (!DELIVERED.contains(order.order().state())) {
            throw new Conflict("not_delivered", CourierTips.NOT_DELIVERED);
        }
        var courier = courier(order);
        if (courier == null) {
            throw new Conflict("no_courier", CourierTips.NO_COURIER);
        }
        if (!eligible(order)) {
            throw new Conflict("tip_window", CourierTips.WINDOW);
        }
        return new Delivered(order, courier);
    }

    private boolean eligible(CustomerOrders.OrderDetail order) {
        var at = order.order().deliveredAt();
        return DELIVERED.contains(order.order().state())
                && at != null
                && clock.instant().isBefore(at.plus(WINDOW));
    }

    private @Nullable String courier(CustomerOrders.OrderDetail order) {
        if ("pickup".equals(order.order().delivery())) {
            return null;
        }
        return deliveries
                .of(order.order().id())
                .map(DeliveryStatuses.Status::courierUserId)
                .filter(Objects::nonNull)
                .orElse(null);
    }
}
