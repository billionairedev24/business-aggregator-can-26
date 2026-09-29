package ca.northline.availability.domain;

import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A member's bookable hours for each weekday; several ranges per day (split shift / evening). An empty list = not
 * bookable that day. Rules (not in validation-rules.md; see docs/DECISIONS.md "Operations"): each range ends after
 * it starts, and ranges of one day don't overlap. Field paths: {@code days.mon[1]}.
 */
public record WeeklyHours(Map<DayOfWeek, List<TimeRange>> days) {

    public static final String END_BEFORE_START = "End time must be after start time.";
    public static final String OVERLAP = "These hours overlap another range on the same day.";

    public WeeklyHours {
        var copy = new EnumMap<DayOfWeek, List<TimeRange>>(DayOfWeek.class);
        var errors = new ArrayList<Violation>();
        for (var day : DayOfWeek.values()) {
            var ranges = days.getOrDefault(day, List.of());
            for (int i = 0; i < ranges.size(); i++) {
                var r = ranges.get(i);
                var path = "days.%s[%d]".formatted(key(day), i);
                if (!r.end().isAfter(r.start())) {
                    errors.add(new Violation(path, "range", END_BEFORE_START));
                    continue;
                }
                for (int j = 0; j < i; j++) {
                    if (r.overlaps(ranges.get(j))) {
                        errors.add(new Violation(path, "overlap", OVERLAP));
                        break;
                    }
                }
            }
            copy.put(
                    day,
                    ranges.stream()
                            .sorted(Comparator.comparing(TimeRange::start))
                            .toList());
        }
        if (!errors.isEmpty()) {
            throw new RuleViolation(errors);
        }
        days = Map.copyOf(copy);
    }

    public List<TimeRange> on(DayOfWeek day) {
        return days.getOrDefault(day, List.of());
    }

    /** {@code mon}, {@code tue}, … (JSON keys). */
    public static String key(DayOfWeek day) {
        return day.name().substring(0, 3).toLowerCase(Locale.ROOT);
    }
}
