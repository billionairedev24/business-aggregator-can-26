package ca.northline.availability.domain;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * "Preview · what customers see": start times every {@code interval} minutes inside each range where a job of
 * {@code duration} fits; a start is taken when it overlaps an existing job widened by the travel buffer on both
 * sides (slots = hours − existing jobs − buffer).
 */
public final class SlotPlanner {
    private SlotPlanner() {}

    /** An existing job, in minutes since midnight (local time). */
    public record Busy(int startMin, int endMin) {}

    public record Slot(LocalTime start, boolean free) {}

    public static List<Slot> preview(
            List<TimeRange> hours, List<Busy> jobs, int durationMin, int intervalMin, int bufferMin) {
        if (durationMin <= 0 || intervalMin <= 0) {
            throw new IllegalArgumentException("duration and interval must be positive");
        }
        var slots = new ArrayList<Slot>();
        for (var range : hours) {
            for (int t = range.startMin(); t + durationMin <= range.endMin(); t += intervalMin) {
                int start = t;
                boolean taken = jobs.stream()
                        .anyMatch(
                                b -> start < b.endMin() + bufferMin && start + durationMin > b.startMin() - bufferMin);
                slots.add(new Slot(LocalTime.of(start / 60, start % 60), !taken));
            }
        }
        return List.copyOf(slots);
    }

    /** Start times in one range for the weekly editor ("N slots"), for a job of {@code durationMin}. */
    public static int count(TimeRange range, int durationMin, int intervalMin) {
        int span = range.endMin() - range.startMin() - durationMin;
        return span < 0 ? 0 : span / intervalMin + 1;
    }
}
