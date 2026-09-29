package ca.northline.food.domain;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** "Pause new orders" — auto-resumes after 30 minutes. */
public final class KitchenPause {
    private KitchenPause() {}

    public static final Duration LENGTH = Duration.ofMinutes(30);

    public static Instant until(Instant now) {
        return now.plus(LENGTH);
    }

    public static boolean paused(@Nullable Instant pausedUntil, Instant now) {
        return pausedUntil != null && pausedUntil.isAfter(now);
    }
}
