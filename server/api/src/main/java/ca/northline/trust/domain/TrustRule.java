package ca.northline.trust.domain;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * S-93: the trust &amp; safety rules staff tune in the console (design 03 {@code trust}: tier rules, automatic
 * consequences, the rating floor, plus the keyword lists of the off-platform payment detector and listing vetting).
 * Each rule is a small JSON object with typed fields and a default (design 03 values); an edit replaces the whole value
 * after validation. Rules that are never edited use their defaults.
 */
public enum TrustRule implements CodedEnum {
    /** Registered: verified, 0–19 jobs — listed, request-only booking, 15 % take. */
    TIER_REGISTERED(List.of(Field.integer("takeRateBps", 0, 3000)), Map.of("takeRateBps", 1500)),
    /** Trusted: 20+ jobs · quality ≥ 80 · on-time ≥ 92 % · disputes ≤ 1.5 % → instant book, 12 % take, badge. */
    TIER_TRUSTED(
            List.of(
                    Field.integer("minJobs", 0, 10_000),
                    Field.integer("minQuality", 0, 100),
                    Field.number("minOnTimePct", 0, 100),
                    Field.number("maxDisputePct", 0, 100),
                    Field.integer("takeRateBps", 0, 3000)),
            ordered("minJobs", 20, "minQuality", 80, "minOnTimePct", 92, "maxDisputePct", 1.5, "takeRateBps", 1200)),
    /** Master: 100+ jobs · quality ≥ 85 · on-time ≥ 95 % · disputes ≤ 1 % · photos ≥ 90 % → 9 % take, weekly payouts. */
    TIER_MASTER(
            List.of(
                    Field.integer("minJobs", 0, 10_000),
                    Field.integer("minQuality", 0, 100),
                    Field.number("minOnTimePct", 0, 100),
                    Field.number("maxDisputePct", 0, 100),
                    Field.number("minPhotosPct", 0, 100),
                    Field.integer("takeRateBps", 0, 3000)),
            ordered(
                    "minJobs",
                    100,
                    "minQuality",
                    85,
                    "minOnTimePct",
                    95,
                    "maxDisputePct",
                    1.0,
                    "minPhotosPct",
                    90,
                    "takeRateBps",
                    900)),
    /** Rating floor 4.2 (90-day) → removed from search, coaching checklist, 30 days to recover. */
    RATING_FLOOR(
            List.of(Field.number("rating", 1, 5), Field.integer("days", 7, 365), Field.integer("recoverDays", 1, 365)),
            ordered("rating", 4.2, "days", 90, "recoverDays", 30)),
    /** 3 no-shows in 30 days → instant book off. */
    PROVIDER_NO_SHOWS(
            List.of(Field.integer("count", 1, 20), Field.integer("days", 1, 365)), ordered("count", 3, "days", 30)),
    /** Customer no-show ×2 → reliability score drop; providers see it before accepting. */
    CUSTOMER_NO_SHOWS(List.of(Field.integer("count", 1, 20)), Map.of("count", 2)),
    /** Completion photo missing → escrow release delayed 48 h. */
    MISSING_PHOTO_DELAY(List.of(Field.integer("hours", 0, 240)), Map.of("hours", 48)),
    /**
     * Off-platform payment attempt detected in messages → warning, then suspension. These phrases add to the
     * detector's built-in patterns (phone and email masking, e-transfer, cash, …) — case-insensitive.
     */
    OFF_PLATFORM_PHRASES(
            List.of(Field.words("phrases")),
            Map.of(
                    "phrases",
                    List.of("save the fee", "pay me directly", "without the app", "sans frais de plateforme"))),
    /** Listing vetting: words a listing may not use without proof (restricted claims). Case-insensitive. */
    RESTRICTED_KEYWORDS(
            List.of(Field.words("words")),
            Map.of("words", List.of("guaranteed to pass", "100% guaranteed", "miracle", "cbd", "garanti à 100 %")));

    public static final String NUMBER_RANGE = "Enter a number in the allowed range.";
    public static final String WHOLE_NUMBER = "Enter a whole number in the allowed range.";
    public static final String WORDS_REQUIRED = "Add at least one word or phrase.";
    public static final String WORD_TOO_LONG = "Each word or phrase is at most 60 characters.";
    public static final String TOO_MANY_WORDS = "At most 200 words or phrases.";
    public static final int WORD_MAX = 60;
    public static final int WORDS_MAX = 200;

    /** A field of a rule's value: a whole number, a number, or a list of words / phrases. */
    public record Field(String name, Kind kind, double min, double max) {
        public enum Kind implements CodedEnum {
            INTEGER,
            NUMBER,
            WORDS
        }

        static Field integer(String name, double min, double max) {
            return new Field(name, Kind.INTEGER, min, max);
        }

        static Field number(String name, double min, double max) {
            return new Field(name, Kind.NUMBER, min, max);
        }

        static Field words(String name) {
            return new Field(name, Kind.WORDS, 1, WORDS_MAX);
        }
    }

    @SuppressWarnings("ImmutableEnumChecker") // List.copyOf: unmodifiable, of records
    private final List<Field> fields;

    @SuppressWarnings("ImmutableEnumChecker") // an unmodifiable copy of numbers and unmodifiable lists of strings
    private final Map<String, Object> defaults;

    TrustRule(List<Field> fields, Map<String, Object> defaults) {
        this.fields = List.copyOf(fields);
        this.defaults = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(defaults));
    }

    public List<Field> fields() {
        return fields;
    }

    public Map<String, Object> defaults() {
        return defaults;
    }

    /**
     * The value cleaned up (numbers as numbers, words trimmed, lower-cased and de-duplicated) — or a 422 listing every
     * field that is missing or out of range ({@code value.<field>}).
     */
    public Map<String, Object> validate(Map<String, ?> value) {
        var problems = new ArrayList<Violation>();
        var out = new LinkedHashMap<String, Object>();
        for (var f : fields) {
            var raw = value.get(f.name());
            var path = "value." + f.name();
            if (f.kind() == Field.Kind.WORDS) {
                var words = words(raw);
                if (words == null || words.isEmpty()) {
                    problems.add(new Violation(path, "required", WORDS_REQUIRED));
                } else if (words.size() > WORDS_MAX) {
                    problems.add(new Violation(path, "length", TOO_MANY_WORDS));
                } else if (words.stream().anyMatch(w -> w.length() > WORD_MAX)) {
                    problems.add(new Violation(path, "length", WORD_TOO_LONG));
                } else {
                    out.put(f.name(), words);
                }
            } else {
                var number = raw instanceof Number n ? n.doubleValue() : null;
                var whole = f.kind() == Field.Kind.INTEGER;
                if (number == null || number < f.min() || number > f.max() || (whole && number != Math.rint(number))) {
                    problems.add(new Violation(path, "range", whole ? WHOLE_NUMBER : NUMBER_RANGE));
                } else {
                    out.put(f.name(), whole ? (Object) (long) number.doubleValue() : (Object) number);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }
        return out;
    }

    private static @Nullable List<String> words(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        return list.stream()
                .filter(String.class::isInstance)
                .map(w -> ((String) w).strip().toLowerCase(Locale.ROOT))
                .filter(w -> !w.isEmpty())
                .distinct()
                .toList();
    }

    private static Map<String, Object> ordered(Object... pairs) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
