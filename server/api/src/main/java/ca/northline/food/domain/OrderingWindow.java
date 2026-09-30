package ca.northline.food.domain;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * When a dish or a menu can be ordered (S-57), in Edmonton local time: the dish's availability (editor
 * "Availability": always · lunch 11–2 · after 5 pm · weekends) and its menu's schedule (open hours · a window on some
 * weekdays · catering by quote, never through checkout).
 */
public final class OrderingWindow {
    private OrderingWindow() {}

    static final LocalTime LUNCH_FROM = LocalTime.of(11, 0);
    static final LocalTime LUNCH_TO = LocalTime.of(14, 0);
    static final LocalTime EVENING = LocalTime.of(17, 0);

    public static boolean item(ItemWindow window, LocalDateTime at) {
        var t = at.toLocalTime();
        return switch (window) {
            case ALWAYS -> true;
            case LUNCH -> !t.isBefore(LUNCH_FROM) && t.isBefore(LUNCH_TO);
            case AFTER_5 -> !t.isBefore(EVENING);
            case WEEKENDS -> at.getDayOfWeek() == DayOfWeek.SATURDAY || at.getDayOfWeek() == DayOfWeek.SUNDAY;
        };
    }

    /**
     * @param mode {@code open_hours} | {@code window} | {@code quote} (the menu editor's schedule)
     * @param days ISO weekdays of a window (empty = every day)
     */
    public static boolean menu(
            String mode, List<Integer> days, @Nullable String from, @Nullable String to, LocalDateTime at) {
        return switch (mode) {
            case "quote" -> false;
            case "window" -> {
                if (!days.isEmpty() && !days.contains(at.getDayOfWeek().getValue())) {
                    yield false;
                }
                var t = at.toLocalTime();
                yield (from == null || !t.isBefore(LocalTime.parse(from)))
                        && (to == null || t.isBefore(LocalTime.parse(to)));
            }
            default -> true;
        };
    }
}
