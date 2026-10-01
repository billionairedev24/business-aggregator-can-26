package ca.northline;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.platform.MessageCatalogue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * S-40: every message the api and northline-auth can answer in a 422 has a French wording in
 * {@code docs/spec/validation-messages.fr-CA.tsv}. Reads the sources (like {@link SchemaOwnershipTests}) and collects
 * the messages of Bean Validation annotations ({@code message = ...}), of {@code RuleViolation.of(...)} /
 * {@code new Violation(...)} and of {@code new Conflict(...)}, literal or through a constant, plus every constant of a
 * {@code *Messages} class. Messages built at run time are covered by the catalogue's templates (checked by the
 * platform's MessageCatalogueTest).
 */
class ValidationMessageCatalogueTests {

    private static final List<Path> SOURCES = List.of(Path.of("src/main/java"), Path.of("../auth/src/main/java"));
    private static final Pattern CONSTANT = Pattern.compile(
            "(?:static\\s+final\\s+|^\\s*)String\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*((?:\"(?!\"\")(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?\\s*)+);",
            Pattern.MULTILINE);
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern NAME = Pattern.compile("(?:\\b([A-Z][A-Za-z0-9]*)\\.)?\\b([A-Z][A-Z0-9_]+)\\b");
    private static final Pattern ANNOTATION_MESSAGE =
            Pattern.compile("\\bmessage\\s*=\\s*((?:\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?\\s*)+|[A-Z][\\w.]*)");
    private static final Pattern PLACEHOLDER = Pattern.compile("%[-#+0,(\\d$.]*[sd]|\\{\\w+}");
    private static final Pattern SENTENCE = Pattern.compile("[A-Za-z]{2,}\\s+\\S");
    private static final Map<String, Integer> CALLS =
            Map.of("RuleViolation.of(", 2, "new Violation(", 2, "new Conflict(", 1, "new RuleViolation.Violation(", 2);

    private record Source(Path path, String text) {
        String className() {
            var name = path.getFileName().toString();
            return name.substring(0, name.length() - ".java".length());
        }
    }

    @Test
    void everyValidationMessageHasFrench() throws IOException {
        var sources = sources();
        var constants = constants(sources);
        var messages = new TreeSet<String>();
        for (var s : sources) {
            var m = ANNOTATION_MESSAGE.matcher(s.text());
            while (m.find()) {
                messages.addAll(resolve(m.group(1), s, constants));
            }
            for (var call : CALLS.entrySet()) {
                int at = s.text().indexOf(call.getKey());
                while (at >= 0) {
                    var args = arguments(s.text(), at + call.getKey().length());
                    if (args.size() > call.getValue()) {
                        messages.addAll(resolve(args.get(call.getValue()), s, constants));
                    }
                    at = s.text().indexOf(call.getKey(), at + 1);
                }
            }
            if (s.className().endsWith("Messages")) {
                constants.values().stream()
                        .flatMap(List::stream)
                        .filter(c -> c.owner().equals(s.className()))
                        .forEach(c -> messages.add(c.value()));
            }
        }
        // Sentences only: header names, property dumps and SQL constants that share a constant name are not messages.
        messages.removeIf(
                m -> !SENTENCE.matcher(PLACEHOLDER.matcher(m).replaceAll("")).find());

        var catalogue = MessageCatalogue.frenchCanadian();
        assertThat(messages).hasSizeGreaterThan(300);
        assertThat(messages.stream().filter(m -> !catalogue.covers(m)).toList())
                .as("messages without French in docs/spec/validation-messages.fr-CA.tsv")
                .isEmpty();
    }

    private record Constant(String owner, String value) {}

    private static List<Source> sources() throws IOException {
        var out = new ArrayList<Source>();
        for (var root : SOURCES) {
            try (Stream<Path> files = Files.walk(root)) {
                for (var p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    out.add(new Source(p, Files.readString(p)));
                }
            }
        }
        return out;
    }

    private static Map<String, List<Constant>> constants(List<Source> sources) {
        var out = new HashMap<String, List<Constant>>();
        for (var s : sources) {
            var m = CONSTANT.matcher(s.text());
            while (m.find()) {
                out.computeIfAbsent(m.group(1), _ -> new ArrayList<>())
                        .add(new Constant(s.className(), concatenated(m.group(2))));
            }
        }
        return out;
    }

    /** Literals and constants named in an argument expression (a ternary names several). */
    private static List<String> resolve(String expression, Source in, Map<String, List<Constant>> constants) {
        var out = new ArrayList<String>();
        var withoutLiterals = LITERAL.matcher(expression).replaceAll("\"\"");
        if (withoutLiterals.replaceAll("[\"\\s+]", "").isEmpty()) {
            out.add(concatenated(expression));
            return out;
        }
        var names = NAME.matcher(withoutLiterals);
        while (names.find()) {
            var candidates = constants.getOrDefault(names.group(2), List.of());
            var owner = names.group(1) != null ? names.group(1) : in.className();
            var own = candidates.stream().filter(c -> c.owner().equals(owner)).toList();
            (own.isEmpty() && names.group(1) == null ? candidates : own).forEach(c -> out.add(c.value()));
        }
        return out;
    }

    private static String concatenated(String literals) {
        var out = new StringBuilder();
        var m = LITERAL.matcher(literals);
        while (m.find()) {
            out.append(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return out.toString();
    }

    /** The top-level arguments of a call whose opening parenthesis ends just before {@code from}. */
    private static List<String> arguments(String text, int from) {
        var out = new ArrayList<String>();
        int depth = 0;
        int start = from;
        boolean string = false;
        boolean escaped = false;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (string) {
                string = escaped || c != '"';
                escaped = !escaped && c == '\\';
                continue;
            }
            switch (c) {
                case '"' -> string = true;
                case '(', '[', '{' -> depth++;
                case ')', ']', '}' -> {
                    if (depth == 0) {
                        out.add(text.substring(start, i).strip());
                        return out;
                    }
                    depth--;
                }
                case ',' -> {
                    if (depth == 0) {
                        out.add(text.substring(start, i).strip());
                        start = i + 1;
                    }
                }
                default -> {}
            }
        }
        return out;
    }
}
