package ca.northline.payments.application;

import ca.northline.payments.domain.PlusPeriod;
import ca.northline.payments.domain.PlusSchedule;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-162): {@code payments.plus_schedules}, {@code plus_periods} and {@code plus_attempts} — Northline's
 * own Plus billing. A period is charged at most once: the renewal job locks it ({@code skip locked}: a concurrent run
 * moves on) and claims the attempt's number (primary key) before it calls Stripe.
 */
public interface PlusScheduleStore {

    void insert(PlusSchedule schedule, Instant at);

    void update(PlusSchedule schedule, Instant at);

    Optional<PlusSchedule> schedule(String id);

    /** {@code for update}. */
    Optional<PlusSchedule> lockSchedule(String id);

    /** The household's memberships, newest first. */
    List<PlusSchedule> ofHousehold(String householdId);

    void insert(PlusPeriod period, Instant at);

    void update(PlusPeriod period, Instant at);

    Optional<PlusPeriod> period(String id);

    /** {@code for update skip locked}: empty when another run holds it. */
    Optional<PlusPeriod> lockPeriodIfFree(String id);

    /** {@code for update}. */
    Optional<PlusPeriod> lockPeriod(String id);

    /** The period still to be paid (scheduled, retrying or waiting for the member), if any. */
    Optional<PlusPeriod> open(String scheduleId);

    Optional<PlusPeriod> byPaymentIntent(String paymentIntent);

    /** Newest first. */
    List<PlusPeriod> periods(String scheduleId, int limit);

    /** Ids of open periods whose next try is due by {@code now}, oldest first. */
    List<String> due(Instant now, int limit);

    /** Paid periods whose tax isn't reported to Stripe Tax yet. */
    List<String> untaxed(int limit);

    /** @return false when that attempt was claimed already (a concurrent run) */
    boolean claimAttempt(String periodId, int attempt, Instant at, boolean offSession);

    /** @param outcome {@code paid | declined | authentication_required | processing} */
    void attemptOutcome(
            String periodId, int attempt, String outcome, @Nullable String code, @Nullable String paymentIntent);
}
