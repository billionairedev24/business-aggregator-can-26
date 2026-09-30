package ca.northline.orders.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: {@code orders.delivery_windows} as pooled runs. */
public interface DeliveryWindowStore {

    /**
     * Creates the window unless the market already has one starting then (a market-less window at that time — the dev
     * seed's — counts as the market's).
     */
    void ensure(String market, String slot, Instant startsAt, Instant endsAt, Instant packBy, int capacity);

    /** Windows of {@code market} (or market-less) starting in [from, to), soonest first. */
    List<Window> between(String market, Instant from, Instant to);

    Optional<Window> byId(String windowId);

    /**
     * @param market null for a window created outside the schedule (dev seed)
     * @param slot null when not created by the schedule
     */
    record Window(
            String id,
            @Nullable String market,
            @Nullable String slot,
            @Nullable String label,
            Instant startsAt,
            Instant endsAt,
            Instant packBy,
            int households) {}
}
