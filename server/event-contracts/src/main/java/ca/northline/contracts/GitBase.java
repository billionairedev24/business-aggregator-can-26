package ca.northline.contracts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

/**
 * The base branch's schema files, read with the {@code git} CLI: the files at the merge base of {@code HEAD} and the
 * base ref (the fork point), so a branch is compared with what it started from — schemas added on the base branch
 * since then don't look "deleted" here.
 */
public final class GitBase {

    private final Path repo;
    private final String commit;
    private final String description;

    private GitBase(Path repo, String commit, String description) {
        this.repo = repo;
        this.commit = commit;
        this.description = description;
    }

    /** The merge base of HEAD and {@code ref}, or empty when git, the ref or a common ancestor is unavailable. */
    public static Optional<GitBase> resolve(Path repo, String ref) {
        var verified = git(repo, "rev-parse", "--verify", "--quiet", ref + "^{commit}");
        if (verified.isEmpty()) {
            return Optional.empty();
        }
        return git(repo, "merge-base", "HEAD", ref)
                .map(mb -> new GitBase(
                        repo, mb.strip(), ref + " (merge base " + mb.strip().substring(0, 12) + ")"));
    }

    public String description() {
        return description;
    }

    public Map<String, SchemaFiles.Parsed> files(JsonMapper json) {
        var files = new TreeMap<String, SchemaFiles.Parsed>();
        var listing = git(repo, "ls-tree", "--name-only", commit, SchemaFiles.DIR + "/")
                .orElse("");
        for (var path : listing.lines().filter(l -> l.endsWith(".json")).toList()) {
            var name = path.substring(path.lastIndexOf('/') + 1);
            var text = git(repo, "show", commit + ":" + path)
                    .orElseThrow(() -> new IllegalStateException("git show " + commit + ":" + path + " failed"));
            files.put(name, SchemaFiles.parse(text, json));
        }
        return files;
    }

    private static Optional<String> git(Path repo, String... args) {
        var command = new ArrayList<String>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(false).start();
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.of(out);
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
