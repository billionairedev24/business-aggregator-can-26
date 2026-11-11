package ca.northline.tools.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * S-194 one-click demo data: fills a demo database with a whole marketplace in one transaction —
 *
 * <pre>
 *   java -cp /app/tools:/app/tools/lib/*:/app/resources:/app/classes:/app/libs/* ca.northline.tools.demo.DemoSeed seed|reset
 *   ./gradlew :api:demoSeed [-Pdemo.reset=true]          (make demo-seed ENV=local)
 * </pre>
 *
 * <p>{@code seed} makes or refreshes everything (idempotent: ids come from plan keys, so a re-run updates the same rows
 * and moves today's work to the new today); {@code reset} wipes the demo rows first ({@link DemoReset}), then seeds.
 * Refused without {@code NORTHLINE_DEMO_SEED=true} and under any staging or production profile ({@link DemoGuard}).
 * Connection: {@code DB_URL}, {@code DB_USER}, {@code DB_PASSWORD} as the apps use them. Accounts' credentials come in
 * {@code DEMO_ACCOUNT_<KEY>_PASSWORD} / {@code _TOTP} with {@code TOTP_KEY} (northline-auth's); nothing secret is printed.
 * The summary (counts by department, never a credential) goes to stdout and, with {@code DEMO_SUMMARY_FILE}, to that
 * file as Markdown (the pipeline's run summary).
 */
@Slf4j
public final class DemoSeed {

    private DemoSeed() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("seed", "reset").contains(args[0])) {
            throw new IllegalArgumentException("usage: DemoSeed seed|reset");
        }
        var env = new LinkedHashMap<>(System.getenv());
        System.getProperties().forEach((k, v) -> {
            var key = String.valueOf(k);
            if (key.startsWith("DEMO_") || key.startsWith("DB_") || key.equals("TOTP_KEY") || key.startsWith("STORAGE_")) {
                env.put(key, String.valueOf(v));
            }
        });
        var refusal = DemoGuard.refusal(System.getProperties(), env);
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
        var summary = run("reset".equals(args[0]), env, Instant.now());
        new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, java.nio.charset.StandardCharsets.UTF_8)
                .println(summary);
        var file = env.get("DEMO_SUMMARY_FILE");
        if (file != null && !file.isBlank()) {
            try {
                Files.writeString(Path.of(file), summary, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Seeds (after a reset when asked) and returns the Markdown summary. */
    static String run(boolean reset, Map<String, String> env, Instant now) throws Exception {
        var plan = DemoPlan.load();
        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl(env.getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/northline"));
        dataSource.setUser(env.getOrDefault("DB_USER", "northline"));
        dataSource.setPassword(env.getOrDefault("DB_PASSWORD", "northline"));
        try (var connection = dataSource.getConnection();
                var photos = DemoPhotos.open(env)) {
            connection.setAutoCommit(false);
            var db = new Db(connection);
            var accounts = new DemoAccounts(db, plan, env);
            var missing = accounts.missingCredentials();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("no password or authenticator key for " + missing
                        + " (DEMO_ACCOUNT_<KEY>_PASSWORD / _TOTP; scripts/demo/seed-demo.sh makes them)");
            }
            try {
                Map<String, Integer> wiped = Map.of();
                if (reset) {
                    wiped = new DemoReset(db).run();
                    log.info("Reset: {} demo rows removed", wiped.values().stream().mapToInt(Integer::intValue).sum());
                }
                var market = DemoMarket.resolve(db, env);
                var marketChange = market.prepare(db);
                accounts.seed(market, now);
                var businesses = new DemoBusinesses(db, plan, accounts, photos, market, now);
                var made = businesses.seed();
                for (var a : plan.applications()) {
                    businesses.application(a);
                }
                var activity = new DemoActivity(db, accounts, market, now);
                activity.seed(made, plan.reviewers().size(), "gifts");
                connection.commit();
                return summary(plan, market, marketChange, made, businesses.photosByDepartment(), activity.counts(), wiped, photos.written());
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static String summary(
            DemoPlan plan,
            DemoMarket market,
            String marketChange,
            List<DemoBusinesses.Made> made,
            Map<String, Integer> photos,
            Map<String, Integer> activity,
            Map<String, Integer> wiped,
            int photosWritten) {
        var byDepartment = new TreeMap<String, int[]>();
        var categories = new TreeMap<String, Integer>();
        for (var m : made) {
            var dept = m.business().categories().getFirst().substring(0, m.business().categories().getFirst().indexOf('.'));
            var row = byDepartment.computeIfAbsent(dept, k -> new int[2]);
            row[0]++;
            row[1] += m.listingIds().size();
            m.business().categories().forEach(c -> categories.merge(c, 1, Integer::sum));
        }
        var out = new StringBuilder();
        out.append("### Demo data\n\n");
        out.append("Market **").append(market.city()).append(", ").append(market.province()).append("** (`").append(market.id())
                .append("`, ").append(market.zone()).append(") — ").append(marketChange).append(".\n\n");
        if (!wiped.isEmpty()) {
            out.append("Reset first: ").append(wiped.values().stream().mapToInt(Integer::intValue).sum())
                    .append(" demo rows removed from ").append(wiped.size()).append(" tables.\n\n");
        }
        out.append("| department | businesses | listings / dishes | photos |\n|---|---:|---:|---:|\n");
        byDepartment.forEach((d, r) -> out.append("| ").append(label(d)).append(" | ").append(r[0]).append(" | ").append(r[1])
                .append(" | ").append(photos.getOrDefault(d, 0)).append(" |\n"));
        out.append("\nCategories with at least one live, verified business: **").append(categories.size()).append("** (")
                .append(categories.values().stream().filter(n -> n > 1).count()).append(" with two or more).\n\n");
        out.append("| activity | count |\n|---|---:|\n");
        activity.forEach((k, v) -> out.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        out.append("\nAccounts (credentials: `make demo-creds ENV=<target>`, never printed here):\n\n");
        plan.accounts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.append("- `").append(e.getKey()).append("` — ").append(e.getValue().email()).append(" · ")
                        .append(e.getValue().app()).append(" · ").append(e.getValue().role()).append('\n'));
        out.append("\n").append(photosWritten).append(" photos uploaded to the media store this run.\n");
        return out.toString();
    }

    private static String label(String department) {
        return switch (department) {
            case "service" -> "Services";
            case "shop" -> "Shop";
            case "food" -> "Food";
            default -> department;
        };
    }
}
