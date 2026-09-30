package ca.northline.contracts;

import ca.northline.worker.events.EventSchemas;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-34 — the event schemas ({@code server/api/src/main/resources/events}) as a contract, four checks:
 *
 * <ol>
 *   <li><b>schemas</b>: every file is valid for the JSON Schema subset the worker's {@code EventSchemas} implements
 *       ({@link SchemaRules});
 *   <li><b>events ↔ schemas</b>: every {@code @Externalized} event of the api and northline-auth has the schema of its
 *       type and version, and every schema belongs to a published event type (at a version ≤ the event's) — or to a
 *       module-internal {@code DomainEvent} that documents its payload;
 *   <li><b>samples</b>: each event record, built with representative values and serialised like the outbox does,
 *       validates against its schema ({@link SamplePayloads});
 *   <li><b>breaking changes</b>: against the base branch's files (merge base with {@code --base}, default
 *       {@code origin/main}), nothing is removed, newly required, narrowed or tightened without a new version file
 *       ({@link BreakingChanges}). Skipped with a notice when the base can't be read, unless {@code --require-base}.
 * </ol>
 *
 * {@code ./gradlew :event-contracts:eventSchemas [-PeventSchemas.base=origin/main] [-PeventSchemas.requireBase=true]};
 * exit 1 on any problem, 2 when a required base is missing. {@code ./gradlew build} runs the same through
 * {@code EventContractsTest}.
 */
@Slf4j
public final class EventContractCheck {

    public static final String DEFAULT_BASE = "origin/main";

    /** What to check. */
    public record Options(Path repo, @Nullable String base, boolean requireBase) {}

    /** Problems by check, and notes (a skipped base comparison). */
    public record Report(Map<String, List<String>> problems, List<String> notes, int events, int schemas) {
        public boolean ok() {
            return problems.values().stream().allMatch(List::isEmpty);
        }

        public boolean baseMissing() {
            return notes.stream().anyMatch(n -> n.startsWith(BASE_MISSING));
        }
    }

    static final String SCHEMAS = "1. schemas";
    static final String EVENTS = "2. events ↔ schemas";
    static final String SAMPLES = "3. sample payloads";
    static final String BREAKING = "4. breaking changes";
    static final String BASE_MISSING = "base not available";

    private EventContractCheck() {}

    public static Report run(Options options) {
        var json = JsonMapper.builder().build();
        var schemaProblems = new ArrayList<String>();
        var eventProblems = new ArrayList<String>();
        var sampleProblems = new ArrayList<String>();
        var breakingProblems = new ArrayList<String>();
        var notes = new ArrayList<String>();

        // 1. schemas
        var files = SchemaFiles.read(options.repo(), json);
        var valid = new HashMap<String, JsonNode>();
        files.forEach((file, parsed) -> {
            if (parsed.error() != null) {
                schemaProblems.add(file + ": " + parsed.error());
                return;
            }
            var found = SchemaRules.problems(file, parsed.schema());
            found.forEach(p -> schemaProblems.add(file + ": " + p));
            if (found.isEmpty()) {
                valid.put(file, parsed.schema());
            }
        });
        var schemas = EventSchemas.of(valid);

        // 2. events ↔ schemas
        var events = PublishedEvents.scan();
        var latest = new HashMap<String, Integer>();
        for (var event : events) {
            latest.merge(event.wireType(), event.version(), Math::max);
            if (!files.containsKey(event.schemaFile())) {
                eventProblems.add(event.name() + " (" + event.wireType() + " v" + event.version() + "): no "
                        + SchemaFiles.DIR + "/" + event.schemaFile());
            }
        }
        // Module-internal events may document their payload with a schema (e.g. catalogue.listing_submitted).
        var internal = PublishedEvents.internal().stream()
                .filter(e -> files.containsKey(e.schemaFile()))
                .toList();
        var internalTypes = new HashMap<String, Integer>();
        internal.forEach(e -> internalTypes.merge(e.wireType(), e.version(), Math::max));
        for (var file : files.keySet()) {
            var name = SchemaFiles.NAME.matcher(file);
            if (!name.matches()) {
                continue; // reported by check 1
            }
            var version = Integer.parseInt(name.group(2));
            var published = latest.get(name.group(1));
            if (published == null && internalTypes.containsKey(name.group(1))) {
                published = internalTypes.get(name.group(1));
            }
            if (published == null) {
                eventProblems.add(file + ": no @Externalized event has the type " + name.group(1));
            } else if (version > published) {
                eventProblems.add(file + ": the event publishes " + name.group(1) + " v" + published
                        + " — a schema for a later version has no event yet");
            }
        }

        // 3. samples
        var samples = new SamplePayloads(json);
        for (var event : java.util.stream.Stream.concat(events.stream(), internal.stream())
                .toList()) {
            if (!schemas.knows(event.wireType(), event.version())) {
                continue; // reported by check 1 or 2
            }
            var schema = valid.get(event.schemaFile());
            if (schema == null) {
                continue;
            }
            try {
                for (var sample : samples.samples(event.type(), schema)) {
                    schemas.validate(event.wireType(), event.version(), sample.payload())
                            .orElseThrow()
                            .forEach(p -> sampleProblems.add(
                                    event.name() + " [" + sample.label() + "] " + p + " — " + event.schemaFile()));
                }
            } catch (SamplePayloads.SampleException e) {
                sampleProblems.add(event.name() + ": " + e.getMessage());
            }
        }

        // 4. breaking changes
        var ref = options.base() == null || options.base().isBlank() ? DEFAULT_BASE : options.base();
        var base = GitBase.resolve(options.repo(), ref);
        if (base.isEmpty()) {
            notes.add(BASE_MISSING + ": can't read " + ref + " (shallow clone or no such ref) — breaking changes not "
                    + "checked" + (options.requireBase() ? "" : "; CI passes --require-base"));
        } else {
            notes.add("breaking changes checked against " + base.get().description());
            var before = new TreeMap<String, JsonNode>();
            base.get().files(json).forEach((file, parsed) -> {
                if (parsed.error() == null) {
                    before.put(file, parsed.schema());
                }
            });
            var after = new TreeMap<String, JsonNode>();
            files.forEach((file, parsed) -> {
                if (parsed.error() == null) {
                    after.put(file, parsed.schema());
                }
            });
            BreakingChanges.between(before, after)
                    .forEach((file, found) -> found.forEach(
                            p -> breakingProblems.add(file + ": " + p + " — without a version bump (add " + next(file)
                                    + ", keep this file, return the new version from the event's version())")));
        }
        var problems = new TreeMap<String, List<String>>();
        problems.put(SCHEMAS, List.copyOf(schemaProblems));
        problems.put(EVENTS, List.copyOf(eventProblems));
        problems.put(SAMPLES, List.copyOf(sampleProblems));
        problems.put(BREAKING, List.copyOf(breakingProblems));
        return new Report(problems, notes, events.size(), files.size());
    }

    private static String next(String file) {
        var name = SchemaFiles.NAME.matcher(file);
        return name.matches()
                ? name.group(1) + ".v" + (Integer.parseInt(name.group(2)) + 1) + ".schema.json"
                : "a new version file";
    }

    public static void main(String[] args) {
        Path repo = Path.of("..");
        String base = null;
        var requireBase = false;
        for (var arg : args) {
            if (arg.startsWith("--repo=")) {
                repo = Path.of(arg.substring("--repo=".length()));
            } else if (arg.startsWith("--base=")) {
                base = arg.substring("--base=".length());
            } else if (arg.startsWith("--require-base=")) {
                requireBase = Boolean.parseBoolean(arg.substring("--require-base=".length()));
            } else if (arg.equals("--require-base")) {
                requireBase = true;
            }
        }
        var report = run(new Options(repo, base, requireBase));
        log.info("Event schemas: {} files, {} published events", report.schemas(), report.events());
        report.problems().forEach((check, found) -> {
            if (found.isEmpty()) {
                log.info("✓ {}", check);
            } else {
                log.error("✗ {} — {} problem(s):", check, found.size());
                found.forEach(p -> log.error("    {}", p));
            }
        });
        report.notes().forEach(n -> log.warn("! {}", n));
        if (requireBase && report.baseMissing()) {
            log.error("A base branch is required (fetch it: git fetch origin main)");
            System.exit(2);
        }
        System.exit(report.ok() ? 0 : 1);
    }
}
