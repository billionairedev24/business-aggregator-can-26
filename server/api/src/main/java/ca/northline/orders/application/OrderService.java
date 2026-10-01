package ca.northline.orders.application;

import ca.northline.identity.api.PersonDirectory;
import ca.northline.identity.api.PersonDirectory.Person;
import ca.northline.orders.application.OrderQueries.OrderRow;
import ca.northline.orders.application.OrderUseCases.ListOrders;
import ca.northline.orders.application.OrderUseCases.PackOrder;
import ca.northline.orders.application.OrderUseCases.ViewOrder;
import ca.northline.orders.application.OrderViews.Counts;
import ca.northline.orders.application.OrderViews.OrderBoard;
import ca.northline.orders.application.OrderViews.OrderSummary;
import ca.northline.orders.domain.MerchantOrder;
import ca.northline.orders.domain.OrderEnums.SellerStatus;
import ca.northline.shared.NotFound;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class OrderService implements ListOrders, ViewOrder, PackOrder {

    static final ZoneId ZONE = ZoneId.of("America/Edmonton");

    private final OrderQueries orders;
    private final PersonDirectory people;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public OrderBoard board(String merchantId) {
        var now = clock.instant();
        var startOfToday =
                LocalDate.now(clock.withZone(ZONE)).atStartOfDay(ZONE).toInstant();
        var rows = orders.board(merchantId, startOfToday);
        var names = people.people(
                rows.stream().map(OrderRow::customerId).filter(Objects::nonNull).toList());
        var all = rows.stream().map(r -> summary(r, names)).toList();
        var toPack =
                all.stream().filter(o -> o.status() == SellerStatus.TO_PACK).toList();
        var next = toPack.stream()
                .filter(o -> o.cutoffAt() != null && o.cutoffAt().isAfter(now))
                .min(Comparator.comparing(o -> Objects.requireNonNull(o.cutoffAt())));
        var counts = new Counts(
                toPack.size(),
                count(all, SellerStatus.AWAITING_PICKUP),
                (int) all.stream()
                        .filter(o -> o.status() == SellerStatus.DELIVERED
                                && o.deliveredAt() != null
                                && !o.deliveredAt().isBefore(startOfToday))
                        .count(),
                count(all, SellerStatus.ISSUE),
                next.map(OrderSummary::cutoffAt).orElse(null),
                next.map(OrderSummary::runLabel).orElse(null));
        var list = all.stream()
                .filter(o -> o.status() != SellerStatus.DELIVERED && o.status() != SellerStatus.CANCELLED)
                .sorted(Comparator.comparingInt((OrderSummary o) -> rank(o.status()))
                        .thenComparing(o -> o.windowStartsAt() == null ? Instant.MAX : o.windowStartsAt())
                        .thenComparing(OrderSummary::id))
                .toList();
        return new OrderBoard(list, counts);
    }

    @Override
    public OrderSummary view(String merchantId, String orderId) {
        var row = orders.row(merchantId, orderId).orElseThrow(() -> new NotFound("order", orderId));
        var names = row.customerId() == null ? Map.<String, Person>of() : people.people(List.of(row.customerId()));
        return summary(row, names);
    }

    @Override
    @Transactional
    public OrderSummary pack(String merchantId, String orderId, String actorId) {
        var order = orders.packing(merchantId, orderId).orElseThrow(() -> new NotFound("order", orderId));
        var at = clock.instant();
        var packed = order.pack(actorId, at);
        orders.savePacking(order, actorId, at);
        events.publishEvent(packed);
        return view(merchantId, orderId);
    }

    private static int count(List<OrderSummary> all, SellerStatus status) {
        return (int) all.stream().filter(o -> o.status() == status).count();
    }

    private static int rank(SellerStatus status) {
        return switch (status) {
            case TO_PACK -> 0;
            case AWAITING_PICKUP -> 1;
            case OUT_FOR_DELIVERY -> 2;
            case ISSUE -> 3;
            case DELIVERED -> 4;
            case CANCELLED -> 5;
        };
    }

    private static OrderSummary summary(OrderRow r, Map<String, Person> names) {
        var person = r.customerId() == null ? null : names.get(r.customerId());
        var lines = r.lines();
        var status = MerchantOrder.builder()
                .orderId(r.id())
                .merchantId("")
                .orderState(r.state())
                .lines(lines.stream()
                        .map(l -> new MerchantOrder.Line(
                                l.id(),
                                l.state(),
                                l.id().equals(lines.getFirst().id()) ? r.issueNote() : null))
                        .toList())
                .othersPending(false)
                .build()
                .status();
        return new OrderSummary(
                r.id(),
                r.ref(),
                r.customerId(),
                person == null ? null : person.shortName(),
                r.area(),
                lines,
                lines.stream().mapToLong(l -> l.qty() * l.unitCents()).sum(),
                r.windowStartsAt(),
                r.cutoffAt(),
                r.runLabel(),
                r.placedAt(),
                r.deliveredAt(),
                status,
                r.issueNote());
    }
}
