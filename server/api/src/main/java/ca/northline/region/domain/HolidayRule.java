package ca.northline.region.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * How each Canadian statutory holiday falls in a given year. Which of them a province observes is region data
 * ({@code region.regions.holidays}, V130); this only knows the dates and the names (English, French). Observed-day
 * shifts (a holiday on a Sunday moved to Monday) are not modelled: a business sees the holiday on its own date.
 */
public enum HolidayRule {
    NEW_YEAR("New Year's Day", "Jour de l'An", y -> LocalDate.of(y, 1, 1)),
    FAMILY_DAY("Family Day", "Fête de la famille", y -> nth(y, 2, DayOfWeek.MONDAY, 3)),
    LOUIS_RIEL_DAY("Louis Riel Day", "Journée Louis-Riel", y -> nth(y, 2, DayOfWeek.MONDAY, 3)),
    ISLANDER_DAY("Islander Day", "Fête des Insulaires", y -> nth(y, 2, DayOfWeek.MONDAY, 3)),
    HERITAGE_DAY_FEBRUARY("Heritage Day", "Jour du patrimoine", y -> nth(y, 2, DayOfWeek.MONDAY, 3)),
    GOOD_FRIDAY("Good Friday", "Vendredi saint", y -> easterSunday(y).minusDays(2)),
    EASTER_MONDAY("Easter Monday", "Lundi de Pâques", y -> easterSunday(y).plusDays(1)),
    VICTORIA_DAY("Victoria Day", "Fête de la Reine", y -> mondayOnOrBefore(y, 5, 24)),
    PATRIOTS_DAY("National Patriots' Day", "Journée nationale des patriotes", y -> mondayOnOrBefore(y, 5, 24)),
    INDIGENOUS_PEOPLES_DAY(
            "National Indigenous Peoples Day",
            "Journée nationale des peuples autochtones",
            y -> LocalDate.of(y, 6, 21)),
    SAINT_JEAN_BAPTISTE("Saint-Jean-Baptiste Day", "Fête nationale (Saint-Jean-Baptiste)", y -> LocalDate.of(y, 6, 24)),
    CANADA_DAY("Canada Day", "Fête du Canada", y -> LocalDate.of(y, 7, 1)),
    NUNAVUT_DAY("Nunavut Day", "Jour du Nunavut", y -> LocalDate.of(y, 7, 9)),
    CIVIC_HOLIDAY("Civic Holiday", "Congé civique", y -> nth(y, 8, DayOfWeek.MONDAY, 1)),
    HERITAGE_DAY("Heritage Day", "Jour du patrimoine", y -> nth(y, 8, DayOfWeek.MONDAY, 1)),
    BC_DAY("British Columbia Day", "Jour de la Colombie-Britannique", y -> nth(y, 8, DayOfWeek.MONDAY, 1)),
    SASKATCHEWAN_DAY("Saskatchewan Day", "Fête de la Saskatchewan", y -> nth(y, 8, DayOfWeek.MONDAY, 1)),
    NEW_BRUNSWICK_DAY("New Brunswick Day", "Fête du Nouveau-Brunswick", y -> nth(y, 8, DayOfWeek.MONDAY, 1)),
    DISCOVERY_DAY("Discovery Day", "Jour de la découverte", y -> nth(y, 8, DayOfWeek.MONDAY, 3)),
    LABOUR_DAY("Labour Day", "Fête du Travail", y -> nth(y, 9, DayOfWeek.MONDAY, 1)),
    TRUTH_RECONCILIATION(
            "National Day for Truth and Reconciliation",
            "Journée nationale de la vérité et de la réconciliation",
            y -> LocalDate.of(y, 9, 30)),
    THANKSGIVING("Thanksgiving", "Action de grâce", y -> nth(y, 10, DayOfWeek.MONDAY, 2)),
    REMEMBRANCE_DAY("Remembrance Day", "Jour du Souvenir", y -> LocalDate.of(y, 11, 11)),
    CHRISTMAS("Christmas Day", "Noël", y -> LocalDate.of(y, 12, 25)),
    BOXING_DAY("Boxing Day", "Lendemain de Noël", y -> LocalDate.of(y, 12, 26));

    private final String nameEn;
    private final String nameFr;

    @SuppressWarnings("ImmutableEnumChecker") // a stateless lambda
    private final IntFunction<LocalDate> date;

    HolidayRule(String nameEn, String nameFr, IntFunction<LocalDate> date) {
        this.nameEn = nameEn;
        this.nameFr = nameFr;
        this.date = date;
    }

    /** Stable key for storage and i18n ({@code family_day}, {@code saint_jean_baptiste}, …). */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public LocalDate in(int year) {
        return date.apply(year);
    }

    public String name(Locale locale) {
        return locale.getLanguage().equals("fr") ? nameFr : nameEn;
    }

    public static Optional<HolidayRule> of(String key) {
        return Arrays.stream(values()).filter(r -> r.key().equals(key)).findFirst();
    }

    private static LocalDate nth(int year, int month, DayOfWeek day, int n) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, day));
    }

    /** The Monday on or before the date (Victoria Day / National Patriots' Day: the Monday before May 25). */
    private static LocalDate mondayOnOrBefore(int year, int month, int day) {
        return LocalDate.of(year, month, day).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
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
