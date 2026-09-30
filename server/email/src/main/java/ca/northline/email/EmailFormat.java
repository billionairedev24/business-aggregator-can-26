package ca.northline.email;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Currency;
import java.util.Locale;

/**
 * How values read in the recipient's language: money in CAD, dates and times in {@code America/Edmonton}
 * (CLAUDE.md § Conventions). Templates receive these strings, never raw numbers or instants.
 */
public record EmailFormat(Locale locale, ZoneId zone) {

    public static final ZoneId EDMONTON = ZoneId.of("America/Edmonton");

    public static EmailFormat of(Locale locale) {
        return new EmailFormat(EmailLocales.supported(locale), EDMONTON);
    }

    /** {@code $1,234.56} / {@code 1 234,56 $}. */
    public String money(long cents) {
        var format = NumberFormat.getCurrencyInstance(locale);
        format.setCurrency(Currency.getInstance("CAD"));
        return format.format(cents / 100.0);
    }

    /** {@code Friday, October 2, 2026} / {@code vendredi 2 octobre 2026}. */
    public String date(Instant instant) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
                .withLocale(locale)
                .format(instant.atZone(zone));
    }

    /** The date followed by the time ({@code 9:00 a.m.} / {@code 09 h 00}). */
    public String dateTime(Instant instant) {
        var time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                .withLocale(locale)
                .format(instant.atZone(zone));
        return date(instant) + (locale.getLanguage().equals("fr") ? " à " : " at ") + time;
    }
}
