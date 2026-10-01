package ca.northline.region.api;

import java.time.LocalDate;
import java.util.Locale;

/**
 * A statutory holiday of a province on its date. {@code key} is stable ({@code family_day}, {@code
 * saint_jean_baptiste}, …); the names come with it so no client keeps a holiday list of its own.
 */
public record Holiday(String key, LocalDate date, String nameEn, String nameFr) {

    public String name(Locale locale) {
        return locale.getLanguage().equals("fr") ? nameFr : nameEn;
    }
}
