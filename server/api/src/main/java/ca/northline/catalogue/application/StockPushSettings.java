package ca.northline.catalogue.application;

import java.time.Duration;

/**
 * {@code northline.commerce.stock-push.*} as the application needs it (S-158).
 *
 * @param firstRetry the wait after a push's first passing failure; each next one is 3× longer
 * @param maxRetry the longest wait between two attempts
 * @param maxAttempts attempts in all before the push is {@code failed}
 * @param batch pushes sent per run
 */
public record StockPushSettings(Duration firstRetry, Duration maxRetry, int maxAttempts, int batch) {

    /** firstRetry × 3^(attempts − 1), capped at maxRetry. */
    public Duration delayAfter(int attempts) {
        var millis = (double) firstRetry.toMillis() * Math.pow(3, Math.max(0, attempts - 1));
        return Duration.ofMillis((long) Math.min(millis, (double) maxRetry.toMillis()));
    }
}
