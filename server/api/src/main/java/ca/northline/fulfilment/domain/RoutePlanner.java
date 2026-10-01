package ca.northline.fulfilment.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Orders a run's stops and gives each an ETA (S-86) — a simple, deterministic heuristic, recorded on the run as
 * {@value #HEURISTIC}:
 *
 * <ol>
 *   <li><b>Pickups first</b>, every shop on the run, then the drop-offs (a pooled run collects everything before
 *       delivering; the bags are sealed and scanned at each shop).
 *   <li><b>Shops</b>: nearest neighbour from the westernmost located shop; shops without a location follow, by id.
 *   <li><b>Drop-offs</b>: nearest neighbour from the last shop over the located addresses; addresses without
 *       coordinates follow, by postal code, then street, then order id (postal codes cluster geographically).
 *   <li><b>ETAs</b>: from the run's start; a leg takes {@code minutesPerKm × km} (straight line, at least
 *       {@code minLeg}) when both ends are located, else {@code unknownLeg}; each pickup adds {@code pickupDwell}, each
 *       drop-off {@code dropoffDwell}. Several orders from one shop are one place: one leg, one dwell.
 * </ol>
 *
 * Ties break on ids, so the same input always gives the same plan.
 */
public final class RoutePlanner {

    public static final String HEURISTIC = "nearest-neighbour-v1";

    /**
     * @param minutesPerKm straight-line minutes per km (city traffic)
     * @param minLeg the shortest leg between two located places
     * @param unknownLeg a leg to or from a place without coordinates
     */
    public record Settings(
            double minutesPerKm, Duration minLeg, Duration unknownLeg, Duration pickupDwell, Duration dropoffDwell) {}

    /** A shop to collect {@code orderIds} from. */
    public record Pickup(
            String merchantId,
            List<String> orderIds,
            @Nullable Double lat,
            @Nullable Double lng) {
        public Pickup {
            orderIds = List.copyOf(orderIds);
        }
    }

    public record Dropoff(
            String orderId,
            @Nullable String postal,
            @Nullable String street,
            @Nullable Double lat,
            @Nullable Double lng) {}

    /**
     * One stop of the plan, in order.
     *
     * @param kind {@code pickup} | {@code dropoff}
     * @param merchantId the shop, for a pickup
     */
    public record Stop(
            String kind, String orderId, @Nullable String merchantId, Instant eta) {}

    private final Settings settings;

    public RoutePlanner(Settings settings) {
        this.settings = settings;
    }

    public List<Stop> plan(List<Pickup> pickups, List<Dropoff> dropoffs, Instant start) {
        var shops = orderShops(pickups);
        var drops = orderDropoffs(dropoffs, shops.isEmpty() ? null : point(shops.getLast()));
        var out = new ArrayList<Stop>();
        var at = start;
        @Nullable Point last = null;
        for (var shop : shops) {
            var here = point(shop);
            at = at.plus(leg(last, here, last == null));
            for (var orderId : shop.orderIds().stream().sorted().toList()) {
                out.add(new Stop("pickup", orderId, shop.merchantId(), at));
            }
            at = at.plus(settings.pickupDwell());
            last = here;
        }
        for (var drop : drops) {
            var here = new Point(drop.lat(), drop.lng());
            at = at.plus(leg(last, here, last == null));
            out.add(new Stop("dropoff", drop.orderId(), null, at));
            at = at.plus(settings.dropoffDwell());
            last = here;
        }
        return List.copyOf(out);
    }

    private List<Pickup> orderShops(List<Pickup> pickups) {
        var located = new ArrayList<>(
                pickups.stream().filter(p -> p.lat() != null && p.lng() != null).toList());
        var unlocated = pickups.stream()
                .filter(p -> p.lat() == null || p.lng() == null)
                .sorted(Comparator.comparing(Pickup::merchantId))
                .toList();
        var out = new ArrayList<Pickup>();
        if (!located.isEmpty()) {
            located.sort(Comparator.comparing((Pickup p) -> Objects.requireNonNull(p.lng()))
                    .thenComparing(Pickup::merchantId));
            var current = located.removeFirst();
            out.add(current);
            while (!located.isEmpty()) {
                var from = point(current);
                located.sort(Comparator.comparingDouble((Pickup p) -> km(from, point(p)))
                        .thenComparing(Pickup::merchantId));
                current = located.removeFirst();
                out.add(current);
            }
        }
        out.addAll(unlocated);
        return out;
    }

    private static List<Dropoff> orderDropoffs(List<Dropoff> dropoffs, @Nullable Point start) {
        var located = new ArrayList<>(dropoffs.stream()
                .filter(d -> d.lat() != null && d.lng() != null)
                .toList());
        var unlocated = dropoffs.stream()
                .filter(d -> d.lat() == null || d.lng() == null)
                .sorted(Comparator.comparing((Dropoff d) -> normalized(d.postal()))
                        .thenComparing(d -> normalized(d.street()))
                        .thenComparing(Dropoff::orderId))
                .toList();
        var out = new ArrayList<Dropoff>();
        var from = start == null || !start.located() ? null : start;
        while (!located.isEmpty()) {
            if (from == null) {
                located.sort(Comparator.comparing((Dropoff d) -> Objects.requireNonNull(d.lng()))
                        .thenComparing(Dropoff::orderId));
            } else {
                var origin = from;
                located.sort(Comparator.comparingDouble((Dropoff d) -> km(origin, new Point(d.lat(), d.lng())))
                        .thenComparing(Dropoff::orderId));
            }
            var next = located.removeFirst();
            out.add(next);
            from = new Point(next.lat(), next.lng());
        }
        out.addAll(unlocated);
        return out;
    }

    private Duration leg(@Nullable Point from, Point to, boolean first) {
        if (first) {
            return Duration.ZERO; // the run starts at its first stop
        }
        if (from == null || !from.located() || !to.located()) {
            return settings.unknownLeg();
        }
        var minutes = Math.round(km(from, to) * settings.minutesPerKm());
        var leg = Duration.ofMinutes(minutes);
        return leg.compareTo(settings.minLeg()) < 0 ? settings.minLeg() : leg;
    }

    private static String normalized(@Nullable String s) {
        return s == null ? "~" : s.replace(" ", "").toUpperCase(java.util.Locale.ROOT);
    }

    private static Point point(Pickup p) {
        return new Point(p.lat(), p.lng());
    }

    private record Point(@Nullable Double lat, @Nullable Double lng) {
        boolean located() {
            return lat != null && lng != null;
        }
    }

    private static double km(Point a, Point b) {
        return Geo.km(
                Objects.requireNonNull(a.lat()),
                Objects.requireNonNull(a.lng()),
                Objects.requireNonNull(b.lat()),
                Objects.requireNonNull(b.lng()));
    }
}
