package ca.northline.payments.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.dmfs.rfc5545.DateTime;
import org.dmfs.rfc5545.recur.Freq;
import org.dmfs.rfc5545.recur.InvalidRecurrenceRuleException;
import org.dmfs.rfc5545.recur.RecurrenceRule;
import org.dmfs.rfc5545.recur.RecurrenceRuleIterator;

/**
 * S-162: when a Northline Plus membership renews — an RFC 5545 {@code RRULE} from a {@code DTSTART}, both in UTC
 * (lib-recur computes the occurrences). A plan's rule ({@code FREQ=MONTHLY;INTERVAL=1}, {@code FREQ=YEARLY;INTERVAL=1})
 * is {@link #anchored} to the first paid instant. RFC 5545 skips a month that lacks the start's day (a start on the 31st
 * would never renew in February, April, June, September or November), so a start on the 29th–31st gets
 * {@code BYMONTHDAY=28,…,<day>;BYSETPOS=-1}: a shorter month renews on its last day and the next long month goes back to
 * the anchor; a yearly start on February 29 renews on February 28 in common years the same way. The time of day is the
 * start's, to the second. Plain RFC 5545, so any calendar library reads the stored rule the same.
 *
 * @param rrule the stored rule, without the {@code RRULE:} prefix
 * @param dtstart the first occurrence (truncated to the second)
 */
public record BillingRule(String rrule, Instant dtstart) {

    public static final String MONTHLY = "FREQ=MONTHLY;INTERVAL=1";
    public static final String YEARLY = "FREQ=YEARLY;INTERVAL=1";

    private static final TimeZone UTC = TimeZone.getTimeZone(ZoneOffset.UTC);
    private static final Set<String> PLAN_PARTS = Set.of("FREQ", "INTERVAL");

    public BillingRule {
        dtstart = dtstart.truncatedTo(ChronoUnit.SECONDS);
        parse(rrule);
    }

    /**
     * Checks a plan's configured rule: {@code FREQ=MONTHLY} or {@code FREQ=YEARLY} with an optional {@code INTERVAL}
     * (1–12 months, 1–5 years) and nothing else — the anchoring adds the month-end parts, and {@code COUNT} or
     * {@code UNTIL} would end a membership nobody cancelled.
     *
     * @throws IllegalArgumentException with the reason
     */
    public static String planRule(String rule) {
        var normalized = rule.strip().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("RRULE:")) {
            normalized = normalized.substring("RRULE:".length());
        }
        var parsed = parse(normalized);
        for (var part : normalized.split(";")) {
            var name = part.split("=", 2)[0];
            if (!PLAN_PARTS.contains(name)) {
                throw new IllegalArgumentException(
                        "A Plus plan rule takes FREQ and INTERVAL only, not " + name + ": " + rule);
            }
        }
        var interval = parsed.getInterval();
        switch (parsed.getFreq()) {
            case MONTHLY -> {
                if (interval < 1 || interval > 12) {
                    throw new IllegalArgumentException("A monthly Plus rule renews every 1–12 months: " + rule);
                }
            }
            case YEARLY -> {
                if (interval < 1 || interval > 5) {
                    throw new IllegalArgumentException("A yearly Plus rule renews every 1–5 years: " + rule);
                }
            }
            default -> throw new IllegalArgumentException("A Plus plan rule is FREQ=MONTHLY or FREQ=YEARLY: " + rule);
        }
        return normalized;
    }

    /** The plan's rule from {@code start} on, with the month-end anchor when the start's day is past the 28th. */
    public static BillingRule anchored(String planRule, Instant start) {
        var rule = planRule(planRule);
        var date = start.atOffset(ZoneOffset.UTC);
        var day = date.getDayOfMonth();
        if (day > 28) {
            var days = IntStream.rangeClosed(28, day).mapToObj(String::valueOf).collect(Collectors.joining(","));
            var freq = parse(rule).getFreq();
            var month = freq == Freq.YEARLY ? ";BYMONTH=" + date.getMonthValue() : "";
            rule = rule + month + ";BYMONTHDAY=" + days + ";BYSETPOS=-1";
        }
        return new BillingRule(rule, start);
    }

    /** The first occurrence strictly after {@code instant} (the end of the period that starts at or before it). */
    public Instant after(Instant instant) {
        var it = iterator();
        var target = instant.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1).toEpochMilli();
        if (target > dtstart.toEpochMilli()) {
            it.fastForward(target);
        }
        if (!it.hasNext()) {
            throw new IllegalStateException("The rule " + rrule + " has no occurrence after " + instant);
        }
        return Instant.ofEpochMilli(it.nextMillis());
    }

    /** Occurrence {@code n} ({@code 0} = {@link #dtstart}). */
    public Instant occurrence(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("occurrence " + n);
        }
        var it = iterator();
        var millis = it.nextMillis();
        for (var i = 0; i < n; i++) {
            millis = it.nextMillis();
        }
        return Instant.ofEpochMilli(millis);
    }

    private RecurrenceRuleIterator iterator() {
        return parse(rrule).iterator(new DateTime(UTC, dtstart.toEpochMilli()));
    }

    private static RecurrenceRule parse(String rule) {
        try {
            return new RecurrenceRule(rule);
        } catch (InvalidRecurrenceRuleException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Not an RFC 5545 RRULE: " + rule + " (" + e.getMessage() + ")", e);
        }
    }
}
