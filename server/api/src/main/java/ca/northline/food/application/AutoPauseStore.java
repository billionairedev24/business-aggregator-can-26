package ca.northline.food.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-67): the auto-pause state of {@code food.kitchen_settings} — the threshold, when the kitchen was
 * auto-paused, and its late orders (accepted tickets past their ready-by time), apart from the settings row so the
 * Studio's edits and the auto-pause never overwrite each other.
 */
public interface AutoPauseStore {

    /** Kitchens with auto-pause on, or still marked auto-paused. */
    List<AutoPauseRow> watched(Instant now);

    Optional<AutoPauseRow> of(String merchantId, Instant now);

    /** Marks the kitchen auto-paused; false when it already was (another replica got there first). */
    boolean markPaused(String merchantId, Instant at);

    /** Clears the mark; false when it wasn't set. */
    boolean markResumed(String merchantId);

    /**
     * @param threshold "Auto-pause if late orders ≥" (null = never)
     * @param pausedAt when the kitchen was auto-paused, null when it isn't
     */
    record AutoPauseRow(String merchantId, @Nullable Integer threshold, @Nullable Instant pausedAt, int lateOrders) {}
}
