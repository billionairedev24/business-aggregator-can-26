package ca.northline.shared;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A queue of work waiting for someone (S-91: the console's "Work queue"): how many, and since when the oldest waits.
 *
 * @param oldest when the oldest item started waiting; null when the queue is empty (or the time isn't recorded)
 */
public record Backlog(long count, @Nullable Instant oldest) {

    public static Backlog empty() {
        return new Backlog(0, null);
    }
}
