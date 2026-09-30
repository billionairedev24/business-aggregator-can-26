package ca.northline.shared.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * Retries a call a platform rate-limited (429) or couldn't serve (502/503/504): waits what {@code Retry-After} says
 * (seconds, an HTTP date or an ISO instant), else 0.5 s, 1 s, 2 s … — each wait capped at {@code maxBackoff}; after
 * {@code maxRetries} the error goes up to the caller.
 */
@Slf4j
public final class Backoff {

    /** Waits between attempts; tests keep the waits short through {@code maxBackoff}. */
    public interface Sleeper {
        void sleep(Duration duration);
    }

    /** Thread.sleep (virtual threads: cheap). */
    public static final Sleeper THREAD_SLEEP = d -> {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off", e);
        }
    };

    private final int maxRetries;
    private final Duration maxBackoff;
    private final Sleeper sleeper;
    private final Clock clock;

    public Backoff(int maxRetries, Duration maxBackoff, Sleeper sleeper, Clock clock) {
        this.maxRetries = maxRetries;
        this.maxBackoff = maxBackoff;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    public <T> T call(String what, Supplier<T> call) {
        for (int attempt = 0; ; attempt++) {
            try {
                return call.get();
            } catch (HttpClientErrorException.TooManyRequests
                    | HttpServerErrorException.ServiceUnavailable
                    | HttpServerErrorException.BadGateway
                    | HttpServerErrorException.GatewayTimeout e) {
                if (attempt >= maxRetries) {
                    throw e;
                }
                var wait = retryAfter(e).orElse(Duration.ofMillis(500L << Math.min(attempt, 10)));
                log.info("{}: {} — retry {} of {} in {}", what, e.getStatusCode(), attempt + 1, maxRetries, wait);
                pause(wait);
            }
        }
    }

    public void pause(Duration wait) {
        sleeper.sleep(wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait);
    }

    public int maxRetries() {
        return maxRetries;
    }

    Optional<Duration> retryAfter(HttpStatusCodeException e) {
        var headers = e.getResponseHeaders();
        var value = headers == null ? null : headers.getFirst("Retry-After");
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        var v = value.strip();
        try {
            return Optional.of(Duration.ofMillis((long) (Double.parseDouble(v) * 1000)));
        } catch (NumberFormatException _) {
            // not seconds
        }
        for (var parse : java.util.List.<java.util.function.Function<String, Instant>>of(
                s -> ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant(),
                Instant::parse)) {
            try {
                var d = Duration.between(clock.instant(), parse.apply(v));
                return Optional.of(d.isNegative() ? Duration.ZERO : d);
            } catch (DateTimeParseException _) {
                // try the next form
            }
        }
        return Optional.empty();
    }
}
