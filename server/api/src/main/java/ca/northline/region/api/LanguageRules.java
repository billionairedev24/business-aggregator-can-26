package ca.northline.region.api;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The language rules of a place (S-116, Loi 96 readiness): region configuration, never a place named in code.
 *
 * @param frenchFirst interfaces default to French there (web, Studio, apps, courier app), the Terms are presented in
 *     French first with English only on an express request, and receipts and notifications go out in French unless the
 *     person chose English
 * @param frenchListings what merchants there must write in French before a listing goes live
 */
public record LanguageRules(boolean frenchFirst, FrenchListings frenchListings) {

    /** A place with no language rule: the person's own language, nothing required. */
    public static final LanguageRules NONE = new LanguageRules(false, FrenchListings.OFF);

    /**
     * The language to write to someone there in: their own explicit choice when they made one, else French in a
     * French-first place, else their language (English when unknown).
     *
     * @param chosen the person's recorded language ({@code identity.users.locale}, a UI switch), null when never chosen
     * @param requested the language of the request ({@code Accept-Language}), null when unknown
     */
    public Locale language(@Nullable Locale chosen, @Nullable Locale requested) {
        if (chosen != null) {
            return french(chosen) ? Locale.CANADA_FRENCH : Locale.CANADA;
        }
        if (frenchFirst || (requested != null && french(requested))) {
            return Locale.CANADA_FRENCH;
        }
        return Locale.CANADA;
    }

    private static boolean french(Locale locale) {
        return "fr".equals(locale.getLanguage());
    }
}
