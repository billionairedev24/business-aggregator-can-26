package ca.northline.catalogue.application;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * SKUs for listings saved without one (onboarding's quick forms, the service editor): initials of the name, like the
 * design's {@code SVC-BI} "Brake inspection", with {@code -2}, {@code -3} … appended until unused.
 */
final class SkuGenerator {
    private SkuGenerator() {}

    static String service(String name, Predicate<String> taken) {
        return unique("SVC-" + initials(name), taken);
    }

    static String product(String title, Predicate<String> taken) {
        return unique(initials(title), taken);
    }

    static String initials(String text) {
        var plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        var letters = Arrays.stream(plain.split("[^A-Za-z0-9]+"))
                .filter(w -> !w.isEmpty())
                .limit(4)
                .map(w -> w.substring(0, Character.isDigit(w.charAt(0)) ? Math.min(w.length(), 4) : 1))
                .collect(Collectors.joining())
                .toUpperCase(Locale.ROOT);
        return letters.isEmpty() ? "ITEM" : letters;
    }

    private static String unique(String base, Predicate<String> taken) {
        var candidate = base;
        for (int n = 2; taken.test(candidate); n++) {
            candidate = base + "-" + n;
        }
        return candidate;
    }
}
