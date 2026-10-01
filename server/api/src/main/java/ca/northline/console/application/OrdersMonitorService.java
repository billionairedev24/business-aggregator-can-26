package ca.northline.console.application;

import ca.northline.booking.api.BookingMonitor;
import ca.northline.booking.api.BookingMonitor.MonitoredBooking;
import ca.northline.booking.api.MarketplaceBookings;
import ca.northline.fulfilment.api.DeliveryAlerts;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.merchants.api.BusinessNames;
import ca.northline.orders.api.MarketplaceOrders;
import ca.northline.orders.api.OrderMonitor;
import ca.northline.orders.api.OrderMonitor.MonitoredOrder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link MonitorOrders} from the orders and booking modules' monitors, fulfilment's alerts (stuck runs), and names
 * from identity and merchants — each read through its {@code api} (S-37).
 */
@Service
@RequiredArgsConstructor
class OrdersMonitorService implements MonitorOrders {

    static final Duration WEEK = Duration.ofDays(7);
    /** A provider this late past the start without being en route or on site is late. */
    static final Duration BOOKING_LATE = Duration.ofMinutes(15);
    /** Escrow held this long after the job ended needs a look (design: "Escrow &gt; 48 h"). */
    static final Duration ESCROW_LONG = Duration.ofDays(2);
    /** Rows read per source; the monitor shows at most {@link #ROWS}. */
    static final int READ = 500;

    static final int ROWS = 300;

    private static final Set<Status> ATTENTION = EnumSet.of(Status.ISSUE, Status.LATE, Status.STUCK, Status.ESCROW_48H);
    private static final Set<Status> LIVE = EnumSet.of(Status.NEW, Status.LIVE, Status.LATE, Status.STUCK);
    private static final Set<Status> LATE = EnumSet.of(Status.LATE, Status.STUCK);

    private final Clock clock;
    private final PlaceScope places;
    private final OrderMonitor orders;
    private final BookingMonitor bookings;
    private final MarketplaceOrders orderFigures;
    private final MarketplaceBookings bookingFigures;
    private final DeliveryAlerts alerts;
    private final PersonDirectory people;
    private final BusinessNames businesses;

    @Override
    @Transactional(readOnly = true)
    public Monitor monitor(Query query) {
        var now = clock.instant();
        var place = places.resolve(query.province(), query.market());
        var since = now.minus(WEEK);
        var q = query.q() == null || query.q().isBlank() ? null : query.q().strip();
        var orderList = orders.orders(place.scope(), since, q, READ);
        var bookingList = bookings.bookings(place.scope(), since, q, READ);
        var stuck = alerts.stuck(
                orderList.stream()
                        .filter(o -> open(o.state()))
                        .map(MonitoredOrder::id)
                        .toList(),
                now,
                OverviewService.STUCK_AFTER);
        var customers = people.people(Stream.concat(
                        orderList.stream().map(MonitoredOrder::customerId),
                        bookingList.stream().map(MonitoredBooking::customerId))
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        var names = new HashMap<String, String>();
        var rows = Stream.concat(
                        orderList.stream().map(o -> row(o, stuck.contains(o.id()), now, customers, names)),
                        bookingList.stream().map(b -> row(b, now, customers, names)))
                .sorted(Comparator.comparing(Row::attention)
                        .reversed()
                        .thenComparing(Row::at, Comparator.reverseOrder())
                        .thenComparing(Row::id))
                .toList();
        var counts = new Counts(
                rows.stream().filter(r -> ATTENTION.contains(r.status())).count(),
                rows.stream().filter(r -> LIVE.contains(r.status())).count(),
                rows.stream().filter(r -> r.status() == Status.ESCROW_48H).count(),
                rows.stream().filter(r -> LATE.contains(r.status())).count(),
                rows.size());
        var shown = rows.stream().filter(r -> in(query.view(), r.status())).toList();
        var week = orderFigures.placed(place.scope(), since, now) + bookingFigures.made(place.scope(), since, now);
        return new Monitor(
                now,
                week,
                counts,
                shown.stream().limit(ROWS).toList(),
                shown.size() > ROWS || orderList.size() == READ || bookingList.size() == READ);
    }

    private static boolean in(View view, Status status) {
        return switch (view) {
            case ATTENTION -> ATTENTION.contains(status);
            case LIVE -> LIVE.contains(status);
            case ESCROW -> status == Status.ESCROW_48H;
            case LATE -> LATE.contains(status);
            case ALL -> true;
        };
    }

    private static boolean open(String orderState) {
        return !Set.of("delivered", "confirmed", "refunded", "cancelled").contains(orderState);
    }

    private Row row(
            MonitoredOrder o,
            boolean stuck,
            Instant now,
            Map<String, PersonDirectory.Person> customers,
            Map<String, String> names) {
        Status status;
        Instant since = null;
        if (o.state().equals("cancelled")) {
            status = Status.CANCELLED;
        } else if (o.issue()) {
            status = Status.ISSUE;
        } else if (stuck) {
            status = Status.STUCK;
        } else if (open(o.state())
                && o.windowEndsAt() != null
                && o.windowEndsAt().isBefore(now)) {
            status = Status.LATE;
            since = o.windowEndsAt();
        } else {
            status = switch (o.state()) {
                case "placed" -> Status.NEW;
                case "delivered" -> Status.DELIVERED;
                case "confirmed", "refunded" -> Status.DONE;
                default -> Status.LIVE;
            };
        }
        return new Row(
                o.id(),
                o.ref(),
                "order",
                o.type(),
                customer(o.customerId(), customers),
                o.merchantIds().stream().map(id -> name(id, names)).toList(),
                o.totalCents(),
                o.state(),
                status,
                ATTENTION.contains(status),
                o.placedAt(),
                since);
    }

    private Row row(
            MonitoredBooking b, Instant now, Map<String, PersonDirectory.Person> customers, Map<String, String> names) {
        Instant since = null;
        var ended = b.endsAt() != null ? b.endsAt() : b.startsAt();
        Status status = switch (b.state()) {
            case "requested" -> Status.NEW;
            case "confirmed" -> {
                if (b.startsAt() != null && b.startsAt().plus(BOOKING_LATE).isBefore(now)) {
                    since = b.startsAt();
                    yield Status.LATE;
                }
                yield Status.LIVE;
            }
            case "en_route", "on_site" -> Status.LIVE;
            case "completed" -> {
                if (ended != null && ended.plus(ESCROW_LONG).isBefore(now)) {
                    since = ended;
                    yield Status.ESCROW_48H;
                }
                yield Status.ESCROW;
            }
            case "disputed" -> Status.ISSUE;
            case "cancelled" -> Status.CANCELLED;
            default -> Status.DONE;
        };
        return new Row(
                b.id(),
                b.ref(),
                "booking",
                "service",
                customer(b.customerId(), customers),
                List.of(name(b.merchantId(), names)),
                b.priceCents(),
                b.state(),
                status,
                ATTENTION.contains(status),
                b.createdAt(),
                since);
    }

    private static @Nullable String customer(@Nullable String id, Map<String, PersonDirectory.Person> customers) {
        var person = id == null ? null : customers.get(id);
        return person == null ? null : person.shortName();
    }

    private String name(String merchantId, Map<String, String> names) {
        return names.computeIfAbsent(
                merchantId, id -> businesses.displayName(id).orElse(id));
    }
}
