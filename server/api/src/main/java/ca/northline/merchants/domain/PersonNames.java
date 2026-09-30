package ca.northline.merchants.domain;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Compares the name Stripe Identity read from an ID with the principal's legal name typed in onboarding. Accents, case,
 * apostrophes, periods and hyphens don't matter, and the legal name may carry more names (middle names): every given and family
 * name on the ID must appear in the legal name. "Ravi Sandhu" matches "RAVI SINGH SANDHU" on the ID only the other way
 * round, so both directions are accepted.
 */
public final class PersonNames {
    private PersonNames() {}

    public static IdentityMatch compare(String legalName, @Nullable String firstName, @Nullable String lastName) {
        var read = tokens((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName));
        if (read.isEmpty()) {
            return IdentityMatch.UNAVAILABLE;
        }
        var legal = tokens(legalName);
        if (legal.isEmpty()) {
            return IdentityMatch.MISMATCH;
        }
        return legal.containsAll(read) || read.containsAll(legal) ? IdentityMatch.MATCH : IdentityMatch.MISMATCH;
    }

    static List<String> tokens(String name) {
        var plain = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("['’.]", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip();
        return plain.isEmpty() ? List.of() : Arrays.stream(plain.split(" ")).toList();
    }
}
