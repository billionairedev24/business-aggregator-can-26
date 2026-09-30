package ca.northline.merchants.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Timing of the custom-domain lifecycle ({@code northline.domains.*}, docs/runbooks/custom-domains.md).
 *
 * @param verifyWindow how long a pending domain is checked before it expires (7 days)
 * @param recheck how often a proven domain's records are checked again (6 h)
 * @param grace how long a proven domain that stopped pointing at us keeps serving (72 h)
 * @param lostRecheck how often it is checked during the grace period (30 min)
 * @param failedRecheck how long after a certificate failure the domain is tried again (6 h)
 * @param maxFailures certificate failures in a row after which only the merchant's "Check now" retries (3)
 */
public record DomainPolicy(
        Duration verifyWindow,
        Duration recheck,
        Duration grace,
        Duration lostRecheck,
        Duration failedRecheck,
        int maxFailures) {

    public static final DomainPolicy DEFAULT = new DomainPolicy(
            Duration.ofDays(7),
            Duration.ofHours(6),
            Duration.ofHours(72),
            Duration.ofMinutes(30),
            Duration.ofHours(6),
            3);

    /**
     * Next check of a pending domain: every 5 minutes in its first hour (people add records and wait), every 30 minutes
     * that day, then every 2 hours — never past the end of the verification window.
     */
    public Instant pendingNext(Instant now, Instant since) {
        var age = Duration.between(since, now);
        var step = age.compareTo(Duration.ofHours(1)) < 0
                ? Duration.ofMinutes(5)
                : age.compareTo(Duration.ofDays(1)) < 0 ? Duration.ofMinutes(30) : Duration.ofHours(2);
        var next = now.plus(step);
        var end = since.plus(verifyWindow);
        return next.isAfter(end) ? end : next;
    }
}
