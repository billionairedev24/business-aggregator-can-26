package ca.northline.account.domain;

import ca.northline.shared.RuleViolation;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The choices of Language &amp; region and Dietary &amp; accessibility (design 06 account, S-59) as codes, with the
 * words the account menu shows ("Halal, step-free").
 */
public final class PreferenceRules {
    private PreferenceRules() {}

    public static final String CHOOSE = "Choose from the list.";
    public static final String ALLERGIES = "Keep allergies under 200 characters.";
    public static final String NOTES = "Keep notes for providers under 500 characters.";
    public static final int ALLERGIES_MAX = 200;
    public static final int NOTES_MAX = 500;

    public static final List<String> DIETARY =
            List.of("halal", "kosher", "vegetarian", "vegan", "gluten_free", "dairy_free", "nut_free", "low_sodium");
    public static final List<String> ACCESSIBILITY =
            List.of("step_free", "deaf_text", "low_vision", "service_animal", "knock_loudly", "fragrance_free");
    public static final List<String> DISPLAY = List.of("larger_text", "high_contrast", "reduce_motion");
    public static final Set<String> UNITS = Set.of("metric", "imperial");
    public static final Set<String> TIME_FORMATS = Set.of("12h", "24h");
    public static final Set<String> LANGUAGES = Set.of("en", "fr");
    public static final List<String> PROVINCES =
            List.of("AB", "BC", "MB", "NB", "NL", "NS", "NT", "NU", "ON", "PE", "QC", "SK", "YT");

    private static final Map<String, String[]> DIETARY_WORDS = Map.of(
            "halal", new String[] {"Halal", "Halal"},
            "kosher", new String[] {"Kosher", "Casher"},
            "vegetarian", new String[] {"Vegetarian", "Végétarien"},
            "vegan", new String[] {"Vegan", "Végane"},
            "gluten_free", new String[] {"Gluten-free", "Sans gluten"},
            "dairy_free", new String[] {"Dairy-free", "Sans produits laitiers"},
            "nut_free", new String[] {"Nut-free", "Sans noix"},
            "low_sodium", new String[] {"Low sodium", "Faible en sodium"});

    /** A list of codes, each from {@code allowed} (once), in the given order; null stays null (unchanged). */
    public static @Nullable List<String> codes(@Nullable List<String> values, List<String> allowed, String field) {
        if (values == null) {
            return null;
        }
        if (!allowed.containsAll(values)) {
            throw RuleViolation.of(field, "allowed", CHOOSE);
        }
        return allowed.stream().filter(values::contains).toList();
    }

    public static @Nullable String code(@Nullable String value, Set<String> allowed, String field) {
        if (value != null && !allowed.contains(value)) {
            throw RuleViolation.of(field, "allowed", CHOOSE);
        }
        return value;
    }

    public static @Nullable String province(@Nullable String value) {
        if (value == null) {
            return null;
        }
        var code = value.strip().toUpperCase(Locale.ROOT);
        if (!PROVINCES.contains(code)) {
            throw RuleViolation.of("province", "allowed", CHOOSE);
        }
        return code;
    }

    public static @Nullable String text(@Nullable String value, int max, String field, String message) {
        if (value == null) {
            return null;
        }
        var v = value.strip();
        if (v.length() > max) {
            throw RuleViolation.of(field, "length", message);
        }
        return v;
    }

    /** "Halal" / "Halal" for the menu, in the reader's language. */
    public static String dietaryWord(String code, Locale locale) {
        var words = DIETARY_WORDS.get(code);
        return words == null ? code : words["fr".equals(locale.getLanguage()) ? 1 : 0];
    }
}
