package ca.northline.ai.adapters.budget;

import ca.northline.ai.api.AiRateLimited;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/** Day and minute windows shared by both budget stores: days are America/Edmonton days. */
final class BudgetWindows {
    private BudgetWindows() {}

    static final ZoneId ZONE = ZoneId.of("America/Edmonton");

    static LocalDate day(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }

    static long minute(Clock clock) {
        return clock.millis() / 60_000;
    }

    static Duration untilTomorrow(Clock clock) {
        var tomorrow = day(clock).plusDays(1).atStartOfDay(ZONE).toInstant();
        return Duration.between(clock.instant(), tomorrow);
    }

    static Duration untilNextMinute(Clock clock) {
        return Duration.ofMillis(60_000 - clock.millis() % 60_000);
    }

    static AiRateLimited tooFast(Clock clock) {
        return new AiRateLimited(
                "person_rate", untilNextMinute(clock), "Too many AI requests; wait a minute and try again.");
    }

    static AiRateLimited personSpent(Clock clock) {
        return new AiRateLimited(
                "person_tokens", untilTomorrow(clock), "You've used today's AI allowance; it resets at midnight.");
    }

    static AiRateLimited merchantSpent(Clock clock) {
        return new AiRateLimited(
                "merchant_tokens",
                untilTomorrow(clock),
                "This business has used today's AI allowance; it resets at midnight.");
    }
}
