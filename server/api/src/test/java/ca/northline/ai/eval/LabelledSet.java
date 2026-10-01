package ca.northline.ai.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A labelled eval set, {@code src/test/resources/ai-eval/<name>.json}: {@code {"about": "…", "cases": [{"id", "input",
 * "expected", …, "mock": {…}}]}}. {@code mock} scripts the simulated model (see {@link SimulatedModel}); a live model
 * ignores it.
 */
public record LabelledSet(String name, JsonNode root) {

    static final JsonMapper JSON = JsonMapper.builder().build();

    public static LabelledSet load(String name) {
        try (var in = LabelledSet.class.getResourceAsStream("/ai-eval/" + name + ".json")) {
            if (in == null) {
                throw new IllegalArgumentException("No eval set ai-eval/" + name + ".json");
            }
            return new LabelledSet(name, JSON.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<JsonNode> cases() {
        var out = new ArrayList<JsonNode>();
        root.path("cases").forEach(out::add);
        return out;
    }

    /** Lower case, typographic apostrophes and minus folded, thousands separators dropped ("1,200" = "1200"). */
    public static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT).replace('’', '\'').replace('−', '-').replaceAll("(?<=\\d),(?=\\d{3})", "");
    }

    /**
     * Every group of {@code mustInclude} (alternatives) found in {@code text}, and none of {@code mustNotInclude}: the
     * misses and leaks, empty when it holds.
     */
    public static List<String> contentMisses(JsonNode c, String text) {
        var norm = normalise(text);
        var misses = new ArrayList<String>();
        for (var group : c.path("mustInclude")) {
            var hit = false;
            for (var alt : group.isArray() ? group : JSON.createArrayNode().add(group)) {
                hit |= norm.contains(normalise(alt.asString()));
            }
            if (!hit) {
                misses.add("missing " + group);
            }
        }
        for (var bad : c.path("mustNotInclude")) {
            if (norm.contains(normalise(bad.asString()))) {
                misses.add("LEAK " + bad.asString());
            }
        }
        return misses;
    }
}
