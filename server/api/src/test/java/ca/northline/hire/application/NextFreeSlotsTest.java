package ca.northline.hire.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.availability.api.ProviderSlots;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * S-119: provider cards' next free start is read once a minute per provider and job length, in the background once one
 * is known — not per card per page view.
 */
class NextFreeSlotsTest {

    static final Instant NOW = Instant.parse("2026-10-02T15:00:00Z");

    /** A clock the test moves. */
    static final class Moving extends Clock {
        Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Counts the calendar reads; the next free start is whatever the test sets. */
    static final class Calendar implements ProviderSlots {
        final AtomicInteger reads = new AtomicInteger();

        @Nullable
        Instant next = NOW.plus(Duration.ofHours(2));

        @Override
        public List<Day> days(
                String merchantId, int durationMin, LocalDate from, int days, @Nullable String customerId) {
            return List.of();
        }

        @Override
        public Optional<Instant> next(String merchantId, int durationMin) {
            reads.incrementAndGet();
            return Optional.ofNullable(next);
        }

        @Override
        public Optional<String> freeMember(
                String merchantId, Instant startsAt, int durationMin, @Nullable String customerId) {
            return Optional.empty();
        }
    }

    final Moving clock = new Moving();
    final Calendar calendar = new Calendar();
    final NextFreeSlots slots = new NextFreeSlots(calendar, clock);

    NextFreeSlotsTest() {
        slots.background = Runnable::run; // refreshes at once
    }

    @Test
    void oneCalendarReadPerProviderAndLengthWithinTheMinute() {
        for (int i = 0; i < 50; i++) {
            assertThat(slots.next("M1", 60)).contains(NOW.plus(Duration.ofHours(2)));
        }
        slots.next("M1", 30);
        slots.next("M2", 60);
        assertThat(calendar.reads).hasValue(3);
    }

    @Test
    void afterTheMinuteTheKeptAnswerIsShownWhileItIsReadAgain() {
        slots.next("M1", 60);
        clock.now = NOW.plus(NextFreeSlots.TTL).minusSeconds(1);
        calendar.next = NOW.plus(Duration.ofHours(3));
        assertThat(slots.next("M1", 60)).contains(NOW.plus(Duration.ofHours(2))); // fresh: no read
        assertThat(calendar.reads).hasValue(1);
        clock.now = NOW.plus(NextFreeSlots.TTL);
        assertThat(slots.next("M1", 60)).contains(NOW.plus(Duration.ofHours(2))); // stale: shown, read again …
        assertThat(calendar.reads).hasValue(2);
        assertThat(slots.next("M1", 60)).contains(NOW.plus(Duration.ofHours(3))); // … and replaced
    }

    @Test
    void aKeptStartThatHasPassedIsNeverShown() {
        calendar.next = NOW.plusSeconds(10);
        slots.next("M1", 60);
        clock.now = NOW.plusSeconds(20);
        calendar.next = NOW.plus(Duration.ofHours(1));
        assertThat(slots.next("M1", 60)).contains(NOW.plus(Duration.ofHours(1)));
        assertThat(calendar.reads).hasValue(2);
    }

    @Test
    void expiredCardsAreReadAgainOneAtATimePerProvider() {
        var queued = new java.util.ArrayList<Runnable>();
        var later = new NextFreeSlots(calendar, clock);
        later.background = queued::add;
        later.next("M1", 60);
        clock.now = NOW.plus(NextFreeSlots.TTL);
        later.next("M1", 60);
        later.next("M1", 60); // already being read: not queued twice
        assertThat(queued).hasSize(1);
        calendar.next = NOW.plus(Duration.ofHours(5));
        queued.getFirst().run();
        assertThat(later.next("M1", 60)).contains(NOW.plus(Duration.ofHours(5)));
        assertThat(calendar.reads).hasValue(2);
    }

    @Test
    void noFreeStartIsRememberedToo() {
        calendar.next = null;
        assertThat(slots.next("M1", 60)).isEmpty();
        assertThat(slots.next("M1", 60)).isEmpty();
        assertThat(calendar.reads).hasValue(1);
    }
}
