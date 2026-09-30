package ca.northline.worker.webhooks;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Exponential back-off over about three days, then failed for good. */
class RetryScheduleTest {

    static final WebhookProperties.Retry DEFAULTS =
            new WebhookProperties.Retry(Duration.ofSeconds(30), 3, Duration.ofHours(12), 13, 0.1);

    @Test
    void thirtySecondsTimesThree_cappedAtTwelveHours_thirteenAttemptsOverAboutThreeDays() {
        var schedule = new RetrySchedule(DEFAULTS, RandomGenerator.of("L64X128MixRandom"));

        assertThat(IntStream.rangeClosed(1, 8).mapToObj(schedule::nominal))
                .containsExactly(
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(90),
                        Duration.ofSeconds(270),
                        Duration.ofSeconds(810),
                        Duration.ofSeconds(2430),
                        Duration.ofSeconds(7290),
                        Duration.ofSeconds(21870),
                        Duration.ofHours(12));
        assertThat(schedule.span()).isBetween(Duration.ofHours(66), Duration.ofHours(75));
        assertThat(schedule.after(12)).isPresent();
        assertThat(schedule.after(13)).isEmpty();
    }

    @Test
    void jitterStaysWithinTenPercent() {
        var schedule = new RetrySchedule(DEFAULTS, RandomGenerator.of("L64X128MixRandom"));
        for (var i = 0; i < 200; i++) {
            assertThat(schedule.after(2).orElseThrow()).isBetween(Duration.ofSeconds(81), Duration.ofSeconds(99));
        }
    }
}
