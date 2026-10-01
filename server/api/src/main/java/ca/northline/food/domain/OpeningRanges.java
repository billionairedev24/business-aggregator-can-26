package ca.northline.food.domain;

import ca.northline.shared.RuleViolation.Violation;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A day's opening ranges ("11:00"–"21:00", 24 h clock, the kitchen's local time). A range ends after it starts (no ranges
 * over midnight) and ranges of one day don't overlap. An empty list = closed.
 */
public record OpeningRanges(List<Range> ranges) {

    private static final Pattern HH_MM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    public record Range(LocalTime from, LocalTime to) {
        public List<String> asText() {
            return List.of(from.toString(), to.toString());
        }
    }

    public OpeningRanges {
        ranges = ranges.stream().sorted(Comparator.comparing(Range::from)).toList();
    }

    public List<List<String>> asText() {
        return ranges.stream().map(Range::asText).toList();
    }

    /**
     * Checks {@code raw} ([[from, to], …]) and adds one violation per broken range to {@code out} under
     * {@code <field>[i].from|to}. Returns the parsed ranges (valid ones only).
     */
    public static OpeningRanges check(String field, List<List<String>> raw, List<Violation> out) {
        var parsed = new ArrayList<Range>();
        for (int i = 0; i < raw.size(); i++) {
            var pair = raw.get(i);
            var at = field + "[" + i + "]";
            if (pair.size() != 2 || !HH_MM.matcher(pair.get(0)).matches()) {
                out.add(new Violation(at + ".from", "format", KitchenMessages.TIME_FORMAT));
                continue;
            }
            if (!HH_MM.matcher(pair.get(1)).matches()) {
                out.add(new Violation(at + ".to", "format", KitchenMessages.TIME_FORMAT));
                continue;
            }
            try {
                var range = new Range(LocalTime.parse(pair.get(0)), LocalTime.parse(pair.get(1)));
                if (!range.to().isAfter(range.from())) {
                    out.add(new Violation(at + ".to", "end_after_start", KitchenMessages.END_AFTER_START));
                    continue;
                }
                if (parsed.stream()
                        .anyMatch(r ->
                                r.from().isBefore(range.to()) && range.from().isBefore(r.to()))) {
                    out.add(new Violation(at + ".from", "overlap", KitchenMessages.OVERLAP));
                    continue;
                }
                parsed.add(range);
            } catch (DateTimeParseException ex) {
                out.add(new Violation(at + ".from", "format", KitchenMessages.TIME_FORMAT));
            }
        }
        return new OpeningRanges(parsed);
    }
}
