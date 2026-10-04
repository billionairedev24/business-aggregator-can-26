package ca.northline.catalogue.domain;

import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Words that name an age-restricted product (alcohol, tobacco and vaping products), English and French. A product
 * using one in a category without an age class goes to a person (vetting flag {@code age_class_mismatch}): a seller
 * can't take wine out of the age check by listing it under groceries. A person decides; false positives ("rum cake")
 * are approved by them.
 */
public final class AgeRestrictedWords {

    private AgeRestrictedWords() {}

    private static final Pattern WORDS = Pattern.compile(
            "\\b(wines?|beers?|lagers?|ales|ciders?|vodka|whisk(?:e)?y|bourbon|scotch|gin|rum|tequila|mezcal|liqueurs?"
                    + "|liquors?|spirits|sake|champagne|prosecco|cava|sangria|seltzer alcoolis[ée]e?|hard seltzer"
                    + "|vins?|bi[èe]res?|alcools?|spiritueux|cigarettes?|cigars?|cigarillos?|tobacco|tabac|snus"
                    + "|vapes?|vapoteuses?|e-?liquids?|e-?liquides?|nicotine|e-?cig(?:arette)?s?|pods? de vapotage)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** The first such word in the text, lower case, or null. */
    public static @Nullable String find(String text) {
        var m = WORDS.matcher(text);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }
}
