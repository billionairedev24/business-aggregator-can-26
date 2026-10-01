package ca.northline.availability.domain;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Availability › Booking rules. Values are limited to the options the design offers; the combinations the baseline
 * columns can't express live in the V041 columns ({@code same_day_cutoff_min}, {@code late_cancel_fee_bps},
 * {@code emergency_premium_*}).
 *
 * @param sameDayCutoffMin "Same day by 9 am" = 540 (with {@code minNoticeMin} 0); null otherwise
 * @param lateCancelFeeCents fixed late-cancellation fee, or null when {@code lateCancelFeeBps} is set ("50% of job")
 * @param emergencyPremiumCents / {@code emergencyPremiumBps}: same-day emergency premium; both null = Off
 * @param holidayPremiumCents premium charged on statutory holidays the business opens
 */
public record BookingRules(
        int intervalMin,
        int bufferMin,
        int minNoticeMin,
        @Nullable Integer sameDayCutoffMin,
        int horizonDays,
        int maxJobsPerDay,
        AcceptMode acceptMode,
        int rescheduleFreeMin,
        @Nullable Long lateCancelFeeCents,
        @Nullable Integer lateCancelFeeBps,
        @Nullable Long emergencyPremiumCents,
        @Nullable Integer emergencyPremiumBps,
        long holidayPremiumCents,
        Set<String> serviceAreas) {

    public static final String NOT_AN_OPTION = "Choose one of the options.";

    public static final Set<Integer> INTERVALS = Set.of(15, 30, 60);
    public static final Set<Integer> BUFFERS = Set.of(0, 15, 20, 30, 45);
    public static final Set<Integer> NOTICES = Set.of(0, 60, 180, 1440, 2880);
    public static final Set<Integer> HORIZONS = Set.of(14, 28, 42, 90);
    public static final Set<Integer> MAX_JOBS = Set.of(3, 4, 5, 6, 99);
    public static final Set<Integer> RESCHEDULE = Set.of(180, 720, 1440);
    public static final Set<Long> LATE_FEES = Set.of(0L, 2500L, 3500L);
    public static final Set<Long> PREMIUMS = Set.of(2500L, 5000L);

    /** instant = confirmed when paid · approve = confirm within 2 h · request = customer describes, you propose. */
    public enum AcceptMode implements CodedEnum {
        INSTANT,
        APPROVE,
        REQUEST
    }

    public BookingRules {
        serviceAreas = Set.copyOf(new TreeSet<>(serviceAreas));
        var errors = new ArrayList<Violation>();
        check(errors, "intervalMin", INTERVALS.contains(intervalMin));
        check(errors, "bufferMin", BUFFERS.contains(bufferMin));
        check(
                errors,
                "minNoticeMin",
                NOTICES.contains(minNoticeMin)
                        && (sameDayCutoffMin == null || (minNoticeMin == 0 && sameDayCutoffMin == 540)));
        check(errors, "horizonDays", HORIZONS.contains(horizonDays));
        check(errors, "maxJobsPerDay", MAX_JOBS.contains(maxJobsPerDay));
        check(errors, "rescheduleFreeMin", RESCHEDULE.contains(rescheduleFreeMin));
        check(
                errors,
                "lateCancelFee",
                (lateCancelFeeCents != null && lateCancelFeeBps == null && LATE_FEES.contains(lateCancelFeeCents))
                        || (lateCancelFeeCents == null && Integer.valueOf(5000).equals(lateCancelFeeBps)));
        check(
                errors,
                "emergencyPremium",
                (emergencyPremiumCents == null && emergencyPremiumBps == null)
                        || (emergencyPremiumCents != null
                                && emergencyPremiumBps == null
                                && PREMIUMS.contains(emergencyPremiumCents))
                        || (emergencyPremiumCents == null
                                && Integer.valueOf(2500).equals(emergencyPremiumBps)));
        check(errors, "holidayPremiumCents", holidayPremiumCents >= 0 && holidayPremiumCents <= 100_000);
        if (!errors.isEmpty()) {
            throw new RuleViolation(errors);
        }
    }

    /**
     * Rules whose service areas must be zones the business's market offers (region data, S-134), with every problem
     * reported at once: the option checks of the record, then {@code serviceAreas}.
     */
    public static BookingRules offeredIn(Collection<String> zones, Supplier<BookingRules> rules, Set<String> areas) {
        var errors = new ArrayList<Violation>();
        BookingRules built = null;
        try {
            built = rules.get();
        } catch (RuleViolation e) {
            errors.addAll(e.getViolations());
        }
        if (!zones.containsAll(areas)) {
            errors.add(new Violation("serviceAreas", "option", NOT_AN_OPTION));
        }
        if (built == null || !errors.isEmpty()) {
            throw new RuleViolation(errors);
        }
        return built;
    }

    /** The same rules with other service areas. */
    public BookingRules withServiceAreas(Set<String> areas) {
        return new BookingRules(
                intervalMin,
                bufferMin,
                minNoticeMin,
                sameDayCutoffMin,
                horizonDays,
                maxJobsPerDay,
                acceptMode,
                rescheduleFreeMin,
                lateCancelFeeCents,
                lateCancelFeeBps,
                emergencyPremiumCents,
                emergencyPremiumBps,
                holidayPremiumCents,
                areas);
    }

    /**
     * What a business gets before it saves its own rules (the design's defaults). The service areas are its market's
     * default zones, which the caller adds ({@link #withServiceAreas}); none here.
     */
    public static BookingRules defaults() {
        return new BookingRules(30, 20, 60, null, 14, 5, AcceptMode.INSTANT, 180, 0L, null, null, null, 5000, Set.of());
    }

    private static void check(List<Violation> errors, String field, boolean ok) {
        if (!ok) {
            errors.add(new Violation(field, "option", NOT_AN_OPTION));
        }
    }
}
