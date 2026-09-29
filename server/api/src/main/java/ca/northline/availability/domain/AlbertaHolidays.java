package ca.northline.availability.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Alberta statutory (general) holidays plus the two optional ones businesses commonly observe (Heritage Day, Boxing
 * Day, as the design lists). Closed by default; a business may open one at its holiday premium.
 */
public final class AlbertaHolidays {
    private AlbertaHolidays() {}

    /** {@code key} is stable for i18n ({@code thanksgiving}, {@code christmas}, …). */
    public record Holiday(String key, LocalDate date) {}

    /** The next {@code count} holidays on or after {@code from}. */
    public static List<Holiday> upcoming(LocalDate from, int count) {
        var all = new ArrayList<Holiday>();
        all.addAll(of(from.getYear()));
        all.addAll(of(from.getYear() + 1));
        return all.stream()
                .filter(h -> !h.date().isBefore(from))
                .sorted(Comparator.comparing(Holiday::date))
                .limit(count)
                .toList();
    }

    public static List<Holiday> of(int year) {
        var easter = easterSunday(year);
        return List.of(
                new Holiday("new_year", MonthDay.of(1, 1).atYear(year)),
                new Holiday("family_day", nth(year, 2, DayOfWeek.MONDAY, 3)),
                new Holiday("good_friday", easter.minusDays(2)),
                new Holiday(
                        "victoria_day",
                        LocalDate.of(year, 5, 24).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))),
                new Holiday("canada_day", LocalDate.of(year, 7, 1)),
                new Holiday("heritage_day", nth(year, 8, DayOfWeek.MONDAY, 1)),
                new Holiday("labour_day", nth(year, 9, DayOfWeek.MONDAY, 1)),
                new Holiday("thanksgiving", nth(year, 10, DayOfWeek.MONDAY, 2)),
                new Holiday("remembrance_day", LocalDate.of(year, 11, 11)),
                new Holiday("christmas", LocalDate.of(year, 12, 25)),
                new Holiday("boxing_day", LocalDate.of(year, 12, 26)));
    }

    public static boolean isHoliday(LocalDate date) {
        return of(date.getYear()).stream().anyMatch(h -> h.date().equals(date));
    }

    private static LocalDate nth(int year, int month, DayOfWeek day, int n) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, day));
    }

    /** Anonymous Gregorian algorithm (Meeus/Jones/Butcher). */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
