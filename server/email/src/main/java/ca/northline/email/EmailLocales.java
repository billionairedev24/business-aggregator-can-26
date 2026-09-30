package ca.northline.email;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Emails are written in Canadian English or Canadian French (the profile's {@code locale}: en-CA | fr-CA). */
public final class EmailLocales {

    public static final Locale ENGLISH = Locale.CANADA;
    public static final Locale FRENCH = Locale.CANADA_FRENCH;

    private EmailLocales() {}

    /** French for any {@code fr} locale, English otherwise. */
    public static Locale supported(@Nullable Locale locale) {
        return locale != null && locale.getLanguage().equals("fr") ? FRENCH : ENGLISH;
    }

    /** From a profile value or language tag ({@code fr-CA}, {@code fr}, {@code en-CA}, null). */
    public static Locale of(@Nullable String tag) {
        return tag == null || tag.isBlank() ? ENGLISH : supported(Locale.forLanguageTag(tag.strip()));
    }
}
