package ca.northline.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real check on this repository — what {@code ./gradlew build} runs (breaking changes against
 * {@code -PeventSchemas.base} or {@code origin/main} when git has it) — and the git plumbing of check 4 on a scratch
 * repository.
 */
class EventContractsTest {

    static final Path REPO = Path.of(System.getProperty("northline.repo", "../.."));

    @Test
    void theEventSchemasHoldTheirContract() {
        var report = EventContractCheck.run(
                new EventContractCheck.Options(REPO, System.getProperty("northline.eventSchemas.base"), false));

        assertThat(report.events()).as("@Externalized events found").isGreaterThan(30);
        assertThat(report.problems())
                .allSatisfy((check, problems) -> assertThat(problems).as(check).isEmpty());
    }

    @Test
    void aBranchThatBreaksAv1SchemaFails_oneThatAddsAv2Passes(@TempDir Path repo) throws Exception {
        var events = repo.resolve(SchemaFiles.DIR);
        Files.createDirectories(events);
        var source = REPO.resolve(SchemaFiles.DIR);
        for (var name : List.of("payments.refund_issued.v1.schema.json", "payments.payout_failed.v1.schema.json")) {
            Files.copy(source.resolve(name), events.resolve(name));
        }
        git(repo, "init", "-q", "-b", "main");
        git(repo, "add", ".");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "base");
        git(repo, "checkout", "-q", "-b", "feature");
        var v1 = events.resolve("payments.refund_issued.v1.schema.json");
        var original = Files.readString(v1);
        Files.writeString(v1, original.replace("\"chargedTo\"\n  ]", "\"chargedTo\",\n    \"caseNumber\"\n  ]"));
        Files.delete(events.resolve("payments.payout_failed.v1.schema.json"));

        var base = GitBase.resolve(repo, "main").orElseThrow();
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var before = new java.util.TreeMap<String, tools.jackson.databind.JsonNode>();
        base.files(json).forEach((f, p) -> before.put(f, p.schema()));
        var after = new java.util.TreeMap<String, tools.jackson.databind.JsonNode>();
        SchemaFiles.read(repo, json).forEach((f, p) -> after.put(f, p.schema()));

        assertThat(BreakingChanges.between(before, after))
                .containsEntry("payments.refund_issued.v1.schema.json", List.of("$.caseNumber: newly required"))
                .containsKey("payments.payout_failed.v1.schema.json");

        // the fix: v1 as it was, the change in v2
        Files.writeString(v1, original);
        Files.copy(
                source.resolve("payments.payout_failed.v1.schema.json"),
                events.resolve("payments.payout_failed.v1.schema.json"));
        Files.writeString(events.resolve("payments.refund_issued.v2.schema.json"), original.replace(":1\"", ":2\""));
        var fixed = new java.util.TreeMap<String, tools.jackson.databind.JsonNode>();
        SchemaFiles.read(repo, json).forEach((f, p) -> fixed.put(f, p.schema()));
        assertThat(BreakingChanges.between(before, fixed)).isEmpty();

        assertThat(GitBase.resolve(repo, "no-such-branch")).isEmpty();
    }

    static void git(Path repo, String... args) throws IOException, InterruptedException {
        var command = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0)
                .as(String.join(" ", command) + "\n" + out)
                .isTrue();
    }
}
