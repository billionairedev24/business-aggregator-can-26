package ca.northline.payments.domain;

import static java.time.temporal.TemporalAdjusters.lastDayOfMonth;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.RuleViolation;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * When scheduled payouts run (design 02 Payouts → Change schedule): weekly on a weekday (default Friday), every
 * business day, monthly on the 1st / 15th / last day, or manual. Payouts run at 9:00 in the business's time zone. The optional
 * reserve keeps money in the balance to cover refunds.
 *
 * @param weekday ISO day of week 1–5 (weekly only)
 */
public record PayoutSchedule(
        Frequency frequency,
        @Nullable Integer weekday,
        @Nullable MonthlyAnchor monthlyAnchor,
        Reserve reserve) {

    public static final LocalTime PAYOUT_TIME = LocalTime.of(9, 0);

    /** Weekly on Friday, no reserve — every new merchant starts here. */
    public static final PayoutSchedule DEFAULT = new PayoutSchedule(Frequency.WEEKLY, 5, null, Reserve.NONE);

    public static final String WEEKDAY_REQUIRED = "Choose a day of the week.";
    public static final String MONTH_DAY_REQUIRED = "Choose a day of the month.";

    public enum Frequency implements CodedEnum {
        WEEKLY,
        DAILY,
        MONTHLY,
        MANUAL
    }

    public enum MonthlyAnchor implements CodedEnum {
        FIRST,
        FIFTEENTH,
        LAST
    }

    /** "Hold back a reserve": None · Keep $500 in balance · Keep 10% of each payout. */
    public enum Reserve implements CodedEnum {
        NONE,
        KEEP_500,
        PERCENT_10;

        public long heldBack(long availableCents) {
            return switch (this) {
                case NONE -> 0;
                case KEEP_500 -> Math.min(availableCents, 50_000);
                case PERCENT_10 -> Fees.percentOf(availableCents, 1000);
            };
        }
    }

    public PayoutSchedule {
        switch (frequency) {
            case WEEKLY -> {
                if (weekday == null || weekday < 1 || weekday > 5) {
                    throw RuleViolation.of("weekday", "required", WEEKDAY_REQUIRED);
                }
                monthlyAnchor = null;
            }
            case MONTHLY -> {
                if (monthlyAnchor == null) {
                    throw RuleViolation.of("monthlyAnchor", "required", MONTH_DAY_REQUIRED);
                }
                weekday = null;
            }
            default -> {
                weekday = null;
                monthlyAnchor = null;
            }
        }
    }

    /**
     * The next scheduled payout strictly after {@code now}, skipping payout days before {@code notBefore} (the 24 h
     * hold after a bank change), payout days and the 9:00 run in {@code zone}. Empty for manual payouts.
     */
    public Optional<Instant> nextAfter(Instant now, @Nullable Instant notBefore, ZoneId zone) {
        if (frequency == Frequency.MANUAL) {
            return Optional.empty();
        }
        var floor = notBefore != null && notBefore.isAfter(now) ? notBefore : now;
        var day = LocalDate.ofInstant(floor, zone);
        for (int i = 0; i < 400; i++, day = day.plusDays(1)) {
            if (isPayoutDay(day)) {
                var at = day.atTime(PAYOUT_TIME).atZone(zone).toInstant();
                if (at.isAfter(now) && !at.isBefore(floor)) {
                    return Optional.of(at);
                }
            }
        }
        throw new IllegalStateException("No payout day within a year for " + this);
    }

    /** Payout days a scheduled run pays on. */
    public boolean isPayoutDay(LocalDate day) {
        var dow = day.getDayOfWeek();
        return switch (frequency) {
            case WEEKLY -> weekday != null && dow.getValue() == weekday;
            case DAILY -> dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
            case MONTHLY -> day.equals(anchorIn(day));
            case MANUAL -> false;
        };
    }

    private LocalDate anchorIn(LocalDate day) {
        return switch (monthlyAnchor == null ? MonthlyAnchor.FIRST : monthlyAnchor) {
            case FIRST -> day.withDayOfMonth(1);
            case FIFTEENTH -> day.withDayOfMonth(15);
            case LAST -> day.with(lastDayOfMonth());
        };
    }

    /** The weekday the Studio shows on the Payouts nav badge ("Fri"), if the schedule is weekly. */
    public Optional<DayOfWeek> weeklyDay() {
        return Optional.ofNullable(weekday).map(DayOfWeek::of);
    }
}
