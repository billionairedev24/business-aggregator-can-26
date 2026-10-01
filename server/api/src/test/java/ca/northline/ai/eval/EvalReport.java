package ca.northline.ai.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * One eval run of a suite over its labelled set: per case the expected and actual label, pass/fail and why; overall
 * pass rate, precision and recall of a label (for the classifiers), tokens and cost. Written as JSON and Markdown under
 * {@code build/ai-eval/}.
 */
public record EvalReport(String suite, String mode, String model, List<Case> cases) {

    /**
     * @param expected the label the set gives (a tool name, a category, "flag" / "ok")
     * @param actual what the model produced
     */
    public record Case(
            String id, String expected, String actual, boolean passed, String detail, long tokens, double costUsd) {}

    public long passed() {
        return cases.stream().filter(Case::passed).count();
    }

    public double passRate() {
        return cases.isEmpty() ? 0 : (double) passed() / cases.size();
    }

    /** Of the cases the model labelled {@code label}, the share the set labels so too (1 when it labelled none). */
    public double precision(String label) {
        var predicted = cases.stream().filter(c -> c.actual().equals(label)).toList();
        return predicted.isEmpty()
                ? 1
                : (double) predicted.stream()
                                .filter(c -> c.expected().equals(label))
                                .count()
                        / predicted.size();
    }

    /** Of the cases the set labels {@code label}, the share the model found (1 when there are none). */
    public double recall(String label) {
        var relevant = cases.stream().filter(c -> c.expected().equals(label)).toList();
        return relevant.isEmpty()
                ? 1
                : (double) relevant.stream()
                                .filter(c -> c.actual().equals(label))
                                .count()
                        / relevant.size();
    }

    public long tokens() {
        return cases.stream().mapToLong(Case::tokens).sum();
    }

    public double cost() {
        return cases.stream().mapToDouble(Case::costUsd).sum();
    }

    public List<Case> failures() {
        return cases.stream().filter(c -> !c.passed()).toList();
    }

    /** Writes {@code <dir>/<suite>-<mode>.json} and {@code .md}; returns the Markdown. */
    public String write(Path dir) {
        var md = new StringBuilder();
        md.append("# AI eval: ").append(suite).append(" (").append(mode).append(")\n\n");
        md.append("Model: `").append(model).append("`\n\n");
        md.append(String.format(
                Locale.ROOT,
                "| Pass rate | Tokens | Cost |%n| --- | --- | --- |%n| %d/%d (%.0f%%) | %,d | $%.4f |%n%n",
                passed(),
                cases.size(),
                passRate() * 100,
                tokens(),
                cost()));
        md.append("| Case | Expected | Actual | Result | Detail |\n| --- | --- | --- | --- | --- |\n");
        for (var c : cases) {
            md.append(String.format(
                    Locale.ROOT,
                    "| %s | %s | %s | %s | %s |%n",
                    c.id(),
                    c.expected(),
                    c.actual(),
                    c.passed() ? "pass" : "**fail**",
                    Objects.toString(c.detail(), "").replace("|", "\\|").replace("\n", " ")));
        }
        try {
            Files.createDirectories(dir);
            var json = JsonMapper.builder().build();
            var node = json.valueToTree(this);
            ((tools.jackson.databind.node.ObjectNode) node).put("passRate", passRate());
            Files.writeString(
                    dir.resolve(suite + "-" + mode + ".json"),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(node),
                    StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(suite + "-" + mode + ".md"), md.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return md.toString();
    }
}
