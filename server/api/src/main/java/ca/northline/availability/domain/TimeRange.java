package ca.northline.availability.domain;

import java.time.LocalTime;

/** Opening hours within one day, e.g. 7:00 am – 6:00 pm. Validated by {@link WeeklyHours} (it knows the field path). */
public record TimeRange(LocalTime start, LocalTime end) {

    public int startMin() {
        return start.getHour() * 60 + start.getMinute();
    }

    public int endMin() {
        return end.getHour() * 60 + end.getMinute();
    }

    public boolean overlaps(TimeRange other) {
        return startMin() < other.endMin() && other.startMin() < endMin();
    }
}
