package ca.northline.merchants.domain;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compares a registry's business name with the names entered in onboarding. Case, accents, punctuation, "&amp;"/"and",
 * a leading "The" and the legal-form suffix (Ltd., Limited, Inc., Corp., Ltée, LLP, …) don't matter:
 * "PRAIRIE WRENCH AUTOMOTIVE LTD." matches "Prairie Wrench Automotive Limited".
 */
public final class BusinessNames {
    private BusinessNames() {}

    private static final Set<String> FORMS = Set.of(
            "ltd",
            "limited",
            "inc",
            "incorporated",
            "corp",
            "corporation",
            "co",
            "company",
            "ltee",
            "limitee",
            "llp",
            "lp",
            "ulc",
            "plc",
            "srl",
            "sencrl",
            "enr");

    public static boolean matches(Collection<String> expected, String recordName) {
        var record = key(recordName);
        return !record.isEmpty() && expected.stream().map(BusinessNames::key).anyMatch(record::equals);
    }

    static String key(String name) {
        var words = Arrays.stream(Normalizer.normalize(name, Normalizer.Form.NFD)
                        .replaceAll("\\p{M}", "")
                        .toLowerCase(Locale.ROOT)
                        .replace("&", " and ")
                        .replaceAll("['’.]", "")
                        .replaceAll("[^\\p{L}\\p{N}]+", " ")
                        .strip()
                        .split(" "))
                .filter(w -> !w.isEmpty())
                .collect(Collectors.toCollection(java.util.ArrayList::new));
        if (!words.isEmpty() && words.getFirst().equals("the")) {
            words.removeFirst();
        }
        while (words.size() > 1 && FORMS.contains(words.getLast())) {
            words.removeLast();
        }
        return String.join(" ", words);
    }
}
