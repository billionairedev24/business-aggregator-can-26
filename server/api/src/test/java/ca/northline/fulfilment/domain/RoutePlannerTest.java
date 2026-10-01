package ca.northline.fulfilment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** S-86's stop ordering heuristic: pickups first, nearest neighbour where located, postal order otherwise. */
class RoutePlannerTest {

    static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    final RoutePlanner planner = new RoutePlanner(new RoutePlanner.Settings(
            3.0, Duration.ofMinutes(4), Duration.ofMinutes(8), Duration.ofMinutes(5), Duration.ofMinutes(3)));

    @Test
    void pickupsComeFirstFromTheWesternmostShopThenNearestNeighbour() {
        // three shops on a line, west to east: A (lng -3), C (lng -2), B (lng -1); B is listed first
        var plan = planner.plan(
                List.of(
                        new RoutePlanner.Pickup("B", List.of("o2"), 50.0, -1.0),
                        new RoutePlanner.Pickup("A", List.of("o1"), 50.0, -3.0),
                        new RoutePlanner.Pickup("C", List.of("o3", "o1"), 50.0, -2.0)),
                List.of(
                        new RoutePlanner.Dropoff("o1", null, null, 50.0, -0.5),
                        new RoutePlanner.Dropoff("o2", null, null, 50.0, 0.5),
                        new RoutePlanner.Dropoff("o3", null, null, 50.0, -0.9)),
                START);
        assertThat(plan)
                .extracting(RoutePlanner.Stop::kind)
                .containsExactly("pickup", "pickup", "pickup", "pickup", "dropoff", "dropoff", "dropoff");
        assertThat(plan.subList(0, 4)).extracting(RoutePlanner.Stop::merchantId).containsExactly("A", "C", "C", "B");
        // from B (lng -1): o3 (-0.9), then o1 (-0.5), then o2 (0.5)
        assertThat(plan.subList(4, 7)).extracting(RoutePlanner.Stop::orderId).containsExactly("o3", "o1", "o2");
        assertThat(plan.getFirst().eta()).isEqualTo(START);
        // one shop, one place: C's two orders share an ETA
        assertThat(plan.get(1).eta()).isEqualTo(plan.get(2).eta());
        assertThat(plan).extracting(RoutePlanner.Stop::eta).isSorted();
    }

    @Test
    void withoutCoordinatesShopsGoByIdAndDropOffsByPostalCodeWithFixedLegs() {
        var plan = planner.plan(
                List.of(
                        new RoutePlanner.Pickup("S2", List.of("x"), null, null),
                        new RoutePlanner.Pickup("S1", List.of("y"), null, null)),
                List.of(
                        new RoutePlanner.Dropoff("y", "T3K 1A1", "9 Main", null, null),
                        new RoutePlanner.Dropoff("x", "t2a 1a1", "1 Main", null, null)),
                START);
        assertThat(plan).extracting(RoutePlanner.Stop::merchantId).containsExactly("S1", "S2", null, null);
        assertThat(plan.subList(2, 4)).extracting(RoutePlanner.Stop::orderId).containsExactly("x", "y");
        // S1 at start; +5 dwell +8 leg → S2; +5 +8 → x; +3 +8 → y
        assertThat(plan)
                .extracting(RoutePlanner.Stop::eta)
                .containsExactly(
                        START, START.plusSeconds(13 * 60), START.plusSeconds(26 * 60), START.plusSeconds(37 * 60));
    }

    @Test
    void theSameInputAlwaysGivesTheSamePlan() {
        var pickups = List.of(new RoutePlanner.Pickup("A", List.of("b", "a"), 50.0, -1.0));
        var drops = List.of(
                new RoutePlanner.Dropoff("a", null, null, 50.1, -1.0),
                new RoutePlanner.Dropoff("b", null, null, 50.1, -1.0));
        assertThat(planner.plan(pickups, drops, START)).isEqualTo(planner.plan(pickups, drops, START));
        assertThat(planner.plan(pickups, drops, START))
                .extracting(RoutePlanner.Stop::orderId)
                .containsExactly("a", "b", "a", "b");
    }
}
