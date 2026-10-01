package ca.northline.fulfilment.application;

import java.time.Duration;

/** A courier's app sent its position sooner than {@code ping-interval} allows (S-88): 429 with Retry-After. */
public final class TooManyPings extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final long retryAfterMs;

    TooManyPings(Duration interval) {
        super("Send your position at most every " + interval.toSeconds() + " s.");
        this.retryAfterMs = interval.toMillis();
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }
}
