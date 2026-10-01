package ca.northline.platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The fr-CA wording of the API's messages (S-40), read from {@code docs/spec/validation-messages.fr-CA.tsv} (packaged
 * as {@value #RESOURCE}). Messages stay English in the code — the exact text of {@code validation-rules.md} or of the
 * module's constant — and the error handlers translate them on the way out when the caller prefers French.
 *
 * <p>A message built at run time ({@code "Only %d left."}, {@code "Northline isn't open in {province} yet."}) is
 * matched against its template: the parts are captured from the English, passed through the caller's argument
 * translation (place names), amounts are re-written the French way ({@code $12.50} → {@code 12,50 $}), and put into
 * the French template. A French placeholder may ask for a form of the part: {@code {province:in}} is the place with
 * the preposition French needs ("en Alberta", "au Québec"). Exact messages win over templates; longer templates are
 * tried before shorter ones.
 */
public final class MessageCatalogue {

    /** Classpath location of the catalogue (copied there from docs/spec by the platform build). */
    public static final String RESOURCE = "i18n/validation-messages.fr-CA.tsv";

    private static final Pattern PLACEHOLDER =
            Pattern.compile("%(?:(\\d+)\\$)?[-#+0,(]*\\d*(?:\\.\\d+)?[sd]|\\{(\\w+)(?::(\\w+))?}");
    private static final Pattern MONEY = Pattern.compile("^(-?)\\$(\\d{1,3}(?:,\\d{3})*|\\d+)(?:\\.(\\d{2}))?$");

    /** How far a line's French has come (third column). */
    public enum Status {
        /** Wording the apps already showed in French. */
        SHIPPED,
        /** First French wording. */
        NEW,
        /** New wording a translator has to settle first. */
        REVIEW
    }

    /** Turns a part captured from the English into French; {@code form} is the placeholder's form ({@code in}). */
    @FunctionalInterface
    public interface Arguments {
        Arguments AS_IS = (value, _) -> value;

        String french(String value, @Nullable String form);
    }

    /** One line of the catalogue. */
    public record Entry(String english, String french, Status status) {}

    private record Placeholder(@Nullable String name, int position) {}

    private record Template(Entry entry, Pattern english, List<Placeholder> parts, int literalLength) {}

    private final List<Entry> entries;
    private final Map<String, String> exact = new HashMap<>();
    private final List<Template> templates = new ArrayList<>();

    private MessageCatalogue(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        for (var e : this.entries) {
            var parts = placeholders(e.english());
            if (parts.isEmpty()) {
                if (exact.putIfAbsent(e.english(), e.french()) != null) {
                    throw new IllegalStateException("Duplicate message in the catalogue: " + e.english());
                }
                continue;
            }
            checkFrench(e, parts);
            templates.add(new Template(
                    e,
                    pattern(e.english()),
                    parts,
                    PLACEHOLDER.matcher(e.english()).replaceAll("").length()));
        }
        templates.sort(Comparator.comparingInt(Template::literalLength).reversed());
    }

    /** The packaged catalogue, read once. */
    public static MessageCatalogue frenchCanadian() {
        return Packaged.CATALOGUE;
    }

    private static final class Packaged {
        static final MessageCatalogue CATALOGUE = load();

        private static MessageCatalogue load() {
            try (InputStream in = MessageCatalogue.class.getClassLoader().getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(RESOURCE + " is not on the classpath");
                }
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Reads catalogue text: {@code English<TAB>French<TAB>status} per line, {@code #} comments and blank lines skipped. */
    public static MessageCatalogue parse(String text) {
        var entries = new ArrayList<Entry>();
        for (var line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var cols = line.stripTrailing().split("\t", -1);
            if (cols.length != 3 || cols[0].isBlank() || cols[1].isBlank()) {
                throw new IllegalStateException("A catalogue line needs English, French and a status: " + line);
            }
            entries.add(new Entry(cols[0], cols[1], Status.valueOf(cols[2].toUpperCase(Locale.ROOT))));
        }
        return new MessageCatalogue(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Whether the catalogue has a French wording for this exact message or for a template that matches it. */
    public boolean covers(String english) {
        return french(english, Arguments.AS_IS).isPresent();
    }

    /**
     * The message in the request's language: French when {@code acceptLanguage} prefers French and the catalogue has
     * it, else the English unchanged. {@code argument} translates a captured part (a place name) into French.
     */
    public String localize(String english, @Nullable String acceptLanguage, Arguments argument) {
        return prefersFrench(acceptLanguage) ? french(english, argument).orElse(english) : english;
    }

    /** The French wording of an English message, when the catalogue has one. */
    public Optional<String> french(String english, Arguments argument) {
        var direct = exact.get(english);
        if (direct != null) {
            return Optional.of(direct);
        }
        for (var t : templates) {
            Matcher m = t.english().matcher(english);
            if (m.matches()) {
                var values = new ArrayList<String>();
                for (int i = 1; i <= m.groupCount(); i++) {
                    values.add(m.group(i));
                }
                return Optional.of(fill(t, values, argument));
            }
        }
        return Optional.empty();
    }

    /**
     * Whether an {@code Accept-Language} header prefers French over English: the highest-weighted range that names
     * one of them decides. Missing, {@code *}-first or unreadable headers mean English, the spec's language.
     */
    public static boolean prefersFrench(@Nullable String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return false;
        }
        try {
            for (var range : Locale.LanguageRange.parse(acceptLanguage)) {
                if (range.getWeight() <= 0) {
                    continue;
                }
                var language = range.getRange().split("-", 2)[0];
                if (language.equals("fr")) {
                    return true;
                }
                if (language.equals("en") || language.equals("*")) {
                    return false;
                }
            }
        } catch (IllegalArgumentException _) {
            return false;
        }
        return false;
    }

    private static String fill(Template t, List<String> values, Arguments argument) {
        var out = new StringBuilder();
        var m = PLACEHOLDER.matcher(t.entry().french());
        int next = 0;
        while (m.find()) {
            int index;
            if (m.group(2) != null) {
                index = indexOfName(t.parts(), m.group(2));
            } else if (m.group(1) != null) {
                index = Integer.parseInt(m.group(1)) - 1;
            } else {
                index = next++;
            }
            var value = frenchAmount(argument.french(values.get(index), m.group(3)));
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static int indexOfName(List<Placeholder> parts, String name) {
        for (var p : parts) {
            if (name.equals(p.name())) {
                return p.position();
            }
        }
        throw new IllegalStateException("No {" + name + "} in the English");
    }

    private static List<Placeholder> placeholders(String english) {
        var out = new ArrayList<Placeholder>();
        var m = PLACEHOLDER.matcher(english);
        while (m.find()) {
            out.add(new Placeholder(m.group(2), out.size()));
        }
        return out;
    }

    /** The French must use the English placeholders: the same names, as many positional ones, positions in range. */
    private static void checkFrench(Entry e, List<Placeholder> parts) {
        var m = PLACEHOLDER.matcher(e.french());
        int sequential = 0;
        while (m.find()) {
            if (m.group(2) != null) {
                indexOfName(parts, m.group(2));
            } else if (m.group(1) != null) {
                var position = Integer.parseInt(m.group(1));
                if (position < 1 || position > parts.size()) {
                    throw new IllegalStateException("Placeholder out of range in: " + e.french());
                }
            } else {
                sequential++;
            }
        }
        var englishSequential = parts.stream().filter(p -> p.name() == null).count();
        if (sequential != 0 && sequential != englishSequential) {
            throw new IllegalStateException("The French needs the English placeholders: " + e.french());
        }
    }

    private static Pattern pattern(String english) {
        var regex = new StringBuilder("^");
        var m = PLACEHOLDER.matcher(english);
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(english.substring(last, m.start()))).append("(.+?)");
            last = m.end();
        }
        return Pattern.compile(
                regex.append(Pattern.quote(english.substring(last))).append('$').toString(), Pattern.DOTALL);
    }

    /** {@code $1,234.50} → {@code 1 234,50 $}; anything else unchanged. */
    static String frenchAmount(String value) {
        var m = MONEY.matcher(value);
        if (!m.matches()) {
            return value;
        }
        var whole = m.group(2).replace(',', ' ');
        var cents = m.group(3);
        return m.group(1) + whole + (cents == null ? "" : "," + cents) + " $";
    }
}
