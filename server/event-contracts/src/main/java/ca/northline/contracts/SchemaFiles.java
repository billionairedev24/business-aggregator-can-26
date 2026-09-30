package ca.northline.contracts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The event schema files — {@code <type>.v<version>.schema.json} in {@link #DIR} — of a working tree or a commit. */
public final class SchemaFiles {

    /** Relative to the repository root. */
    public static final String DIR = "server/api/src/main/resources/events";

    public static final Pattern NAME = Pattern.compile("([a-z0-9_]+\\.[a-z0-9_]+)\\.v([1-9][0-9]*)\\.schema\\.json");

    private SchemaFiles() {}

    /** File name → parsed schema; a file that isn't JSON is reported under its name with a null-free marker. */
    public static Map<String, Parsed> read(Path repo, JsonMapper json) {
        var files = new TreeMap<String, Parsed>();
        try (var stream = Files.list(repo.resolve(DIR))) {
            for (var file : stream.filter(f -> f.getFileName().toString().endsWith(".json"))
                    .toList()) {
                files.put(file.getFileName().toString(), parse(Files.readString(file), json));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    public static Parsed parse(String text, JsonMapper json) {
        try {
            return new Parsed(json.readTree(text), null);
        } catch (JacksonException e) {
            return new Parsed(json.nullNode(), "not valid JSON: " + e.getOriginalMessage());
        }
    }

    /** A schema file's content, or why it couldn't be read. */
    public record Parsed(
            JsonNode schema,
            @org.jspecify.annotations.Nullable String error) {}
}
