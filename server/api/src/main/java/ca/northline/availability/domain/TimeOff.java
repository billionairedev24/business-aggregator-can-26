package ca.northline.availability.domain;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A closure or special hours for one member or the whole team ({@code memberUserId} null). The reason is private.
 * Messages are not in validation-rules.md; see docs/DECISIONS.md "Operations".
 */
public record TimeOff(
        String id,
        @Nullable String memberUserId,
        LocalDate startsOn,
        LocalDate endsOn,
        Kind kind,
        List<TimeRange> specialRanges,
        @Nullable String reason) {

    public static final String FROM_REQUIRED = "Pick the first day.";
    public static final String TO_BEFORE_FROM = "The last day can't be before the first day.";
    public static final String SPECIAL_HOURS_REQUIRED = "Add the hours you're open.";
    public static final String REASON_TOO_LONG = "At most 120 characters.";

    public enum Kind implements CodedEnum {
        CLOSED,
        SPECIAL
    }

    public TimeOff {
        specialRanges = List.copyOf(specialRanges);
        reason = reason == null || reason.isBlank() ? null : reason.strip();
    }

    /** A new entry from the "Add" form, validated. {@code endsOn} defaults to {@code startsOn} (one day). */
    public static TimeOff create(
            @Nullable String memberUserId,
            @Nullable LocalDate startsOn,
            @Nullable LocalDate endsOn,
            Kind kind,
            List<TimeRange> specialRanges,
            @Nullable String reason) {
        var errors = new ArrayList<Violation>();
        if (startsOn == null) {
            errors.add(new Violation("startsOn", "required", FROM_REQUIRED));
        } else if (endsOn != null && endsOn.isBefore(startsOn)) {
            errors.add(new Violation("endsOn", "range", TO_BEFORE_FROM));
        }
        if (kind == Kind.SPECIAL) {
            if (specialRanges.isEmpty()) {
                errors.add(new Violation("specialRanges", "required", SPECIAL_HOURS_REQUIRED));
            }
            for (int i = 0; i < specialRanges.size(); i++) {
                if (!specialRanges.get(i).end().isAfter(specialRanges.get(i).start())) {
                    errors.add(new Violation("specialRanges[%d]".formatted(i), "range", WeeklyHours.END_BEFORE_START));
                }
            }
        }
        if (reason != null && reason.strip().length() > 120) {
            errors.add(new Violation("reason", "length", REASON_TOO_LONG));
        }
        if (!errors.isEmpty() || startsOn == null) {
            throw new RuleViolation(errors);
        }
        return new TimeOff(
                Ids.next(),
                memberUserId,
                startsOn,
                endsOn == null ? startsOn : endsOn,
                kind,
                kind == Kind.SPECIAL ? specialRanges : List.of(),
                reason);
    }

    public boolean covers(LocalDate day, @Nullable String member) {
        return !day.isBefore(startsOn) && !day.isAfter(endsOn) && (memberUserId == null || memberUserId.equals(member));
    }
}
