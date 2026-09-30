package ca.northline.worker.search;

import ca.northline.searchindex.ListingDocument.MinuteRange;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Weekly hours as {@code integer_range}s of minutes in the week (Monday 00:00 America/Edmonton = 0, 10 080 minutes),
 * the shape "open now" is queried against: {@code food.opening_hours} and {@code availability.availability_rules} both
 * store per ISO weekday (Mon = 1) {@code [["11:00","21:00"], …]}. A range past midnight ({@code ["18:00","02:00"]})
 * continues into the next day (Sunday into Monday). Overlapping ranges (two technicians) are merged.
 */
final class OpenHours {

    static final int DAY = 24 * 60;
    static final int WEEK = 7 * DAY;

    private OpenHours() {}

    /** @param byWeekday ISO weekday → the JSON ranges of that day */
    static List<MinuteRange> of(Map<Integer, List<String>> byWeekday, JsonMapper json) {
        var ranges = new ArrayList<MinuteRange>();
        byWeekday.forEach((weekday, days) -> days.forEach(day -> {
            JsonNode parsed = json.readTree(day);
            parsed.valueStream().forEach(range -> add(ranges, weekday, range));
        }));
        return merge(ranges);
    }

    private static void add(List<MinuteRange> ranges, int weekday, JsonNode range) {
        if (weekday < 1 || weekday > 7 || !range.isArray() || range.size() < 2) {
            return;
        }
        int from;
        int to;
        try {
            from = minutes(range.get(0).asString());
            to = minutes(range.get(1).asString());
        } catch (DateTimeParseException e) {
            return;
        }
        var start = (weekday - 1) * DAY + from;
        var end = (weekday - 1) * DAY + (to <= from ? to + DAY : to);
        if (end <= WEEK) {
            ranges.add(new MinuteRange(start, end));
        } else {
            ranges.add(new MinuteRange(start, WEEK));
            ranges.add(new MinuteRange(0, end - WEEK));
        }
    }

    private static int minutes(String hhmm) {
        return hhmm.equals("24:00") ? DAY : LocalTime.parse(hhmm).toSecondOfDay() / 60;
    }

    static List<MinuteRange> merge(List<MinuteRange> ranges) {
        var sorted = ranges.stream()
                .filter(r -> r.lt() > r.gte())
                .sorted(Comparator.comparingInt(MinuteRange::gte))
                .toList();
        var merged = new ArrayList<MinuteRange>();
        for (var range : sorted) {
            if (!merged.isEmpty() && range.gte() <= merged.getLast().lt()) {
                var last = merged.removeLast();
                merged.add(new MinuteRange(last.gte(), Math.max(last.lt(), range.lt())));
            } else {
                merged.add(range);
            }
        }
        return List.copyOf(merged);
    }

    /** "17:45" → 1065; null for anything else. */
    static @org.jspecify.annotations.Nullable Integer minuteOfDay(String hhmm) {
        try {
            return minutes(hhmm);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
