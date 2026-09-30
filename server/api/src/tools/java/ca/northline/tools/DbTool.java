package ca.northline.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.jspecify.annotations.Nullable;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Database commands, run through Gradle on a workstation (see api/build.gradle.kts) or from the api image as the
 * Kubernetes migration Job (S-16, docs/runbooks/deploy.md § Migrations):
 *
 * <pre>
 *   ./gradlew :api:flywayMigrate  [-Pdb.url=… -Pdb.user=… -Pdb.password=… -Pdb.devSeed=true]
 *   ./gradlew :api:flywayInfo
 *   ./gradlew :api:seedCategories
 *   java -cp /app/tools:/app/resources:/app/classes:/app/libs/* ca.northline.tools.DbTool migrate|info|seed-categories
 * </pre>
 *
 * <p>Connection: system properties {@code db.url}, {@code db.user}, {@code db.password}, else the environment
 * variables {@code DB_URL}, {@code DB_USER}, {@code DB_PASSWORD} the apps use, else the local defaults. Applies
 * {@code classpath:db/migration}; {@code db/seed-dev} (dev personas, V1xx) only with {@code db.devSeed=true} and only
 * for a local run — see {@link #devSeedRefusal}. The image doesn't even contain {@code db/seed-dev}.
 */
@Slf4j
public final class DbTool {

    /** Profiles / environments in which the dev seed must never be applied. */
    static final Set<String> DEPLOYED = Set.of("dev", "staging", "prod", "cloud");

    private DbTool() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: DbTool migrate|info|seed-categories");
        }
        run(args[0], System.getProperties(), System.getenv());
    }

    static void run(String command, Map<Object, Object> properties, Map<String, String> env) {
        var devSeed = Boolean.parseBoolean(String.valueOf(properties.getOrDefault("db.devSeed", "false")));
        var url = setting(properties, env, "db.url", "DB_URL", "jdbc:postgresql://localhost:5432/northline");
        var refusal = devSeedRefusal(profiles(properties, env), url);
        if (devSeed && refusal != null) {
            throw new IllegalStateException(refusal);
        }
        var localDatabase = refusal == null;
        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(setting(properties, env, "db.user", "DB_USER", "northline"));
        dataSource.setPassword(setting(properties, env, "db.password", "DB_PASSWORD", "northline"));
        log.info("Database {} as {}", redact(url), dataSource.getUser());

        switch (command) {
            case "migrate" -> migrate(flyway(dataSource, devSeed, localDatabase));
            case "info" -> {
                for (var m : flyway(dataSource, devSeed, localDatabase).info().all()) {
                    log.info("{} {} {}", m.getVersion(), m.getState(), m.getDescription());
                }
            }
            case "seed-categories" -> log.info("Upserted {} categories", new CategorySeeder(dataSource).seed());
            default -> throw new IllegalArgumentException("unknown command " + command);
        }
    }

    private static void migrate(Flyway flyway) {
        var info = flyway.info();
        var current = info.current();
        var pending = info.pending();
        log.info(
                "Schema at {}; {} pending migration(s){}",
                current == null ? "(empty)" : current.getVersion(),
                pending.length,
                pending.length == 0 ? "" : ": " + describe(pending));
        var result = flyway.migrate();
        var now = flyway.info().current();
        log.info(
                "Applied {} migration(s); schema now at {}",
                result.migrationsExecuted,
                now == null ? "(empty)" : now.getVersion());
    }

    private static String describe(MigrationInfo[] migrations) {
        return String.join(
                ", ",
                Arrays.stream(migrations)
                        .map(m -> "V" + m.getVersion() + " " + m.getDescription())
                        .toList());
    }

    /**
     * Why the dev seed may not be applied, or {@code null} when it may: only when no deployed profile is active
     * ({@code dev}, {@code staging}, {@code prod}, {@code cloud} in {@code spring.profiles.active} /
     * {@code SPRING_PROFILES_ACTIVE} / {@code NORTHLINE_ENVIRONMENT}) and the database is on this machine or behind a
     * local container name without dots (localhost, 127.0.0.1, ::1, postgres, …) — never a managed database host.
     */
    static @Nullable String devSeedRefusal(List<String> profiles, String dbUrl) {
        var deployed = profiles.stream().filter(DEPLOYED::contains).toList();
        if (!deployed.isEmpty()) {
            return "db/seed-dev (dev personas) is local-only and is never applied under " + deployed
                    + ": remove db.devSeed / -Pdb.devSeed=true";
        }
        var host = host(dbUrl);
        if (host != null && !isLocalHost(host)) {
            return "db/seed-dev (dev personas) is local-only: refusing to apply it to " + host
                    + " (only localhost or a local container name)";
        }
        return null;
    }

    static List<String> profiles(Map<Object, Object> properties, Map<String, String> env) {
        var all = new ArrayList<String>();
        for (var value : List.of(
                String.valueOf(properties.getOrDefault("spring.profiles.active", "")),
                env.getOrDefault("SPRING_PROFILES_ACTIVE", ""),
                env.getOrDefault("NORTHLINE_ENVIRONMENT", ""))) {
            for (var p : value.split(",")) {
                if (!p.isBlank()) {
                    all.add(p.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return all;
    }

    private static @Nullable String host(String jdbcUrl) {
        var prefix = "jdbc:postgresql://";
        if (!jdbcUrl.startsWith(prefix)) {
            return null;
        }
        var rest = jdbcUrl.substring(prefix.length());
        var end = rest.indexOf('/');
        var hostPort = end < 0 ? rest : rest.substring(0, end);
        if (hostPort.startsWith("[")) {
            var close = hostPort.indexOf(']');
            return close < 0 ? hostPort.substring(1) : hostPort.substring(1, close);
        }
        var colon = hostPort.lastIndexOf(':');
        return colon < 0 ? hostPort : hostPort.substring(0, colon);
    }

    private static boolean isLocalHost(String host) {
        return host.isEmpty()
                || host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("host.docker.internal")
                || (!host.contains(".") && !host.contains(":"));
    }

    private static String setting(
            Map<Object, Object> properties,
            Map<String, String> env,
            String property,
            String variable,
            String fallback) {
        var fromProperty = properties.get(property);
        if (fromProperty != null && !String.valueOf(fromProperty).isBlank()) {
            return String.valueOf(fromProperty);
        }
        var fromEnv = env.get(variable);
        return fromEnv == null || fromEnv.isBlank() ? fallback : fromEnv;
    }

    private static String redact(String url) {
        return url.replaceAll("password=[^&]*", "password=***");
    }

    /**
     * The dev seed (V100–V109, V121) interleaves with the migrations of later ranges (V110+): with it, a database
     * migrated without the seed first takes the seed files afterwards (out of order); without it, a local database the
     * {@code local} profile seeded doesn't fail validation over seed files this run doesn't resolve. A deployed database
     * never has the seed and keeps Flyway's strict validation.
     */
    private static Flyway flyway(PGSimpleDataSource dataSource, boolean devSeed, boolean localDatabase) {
        var locations = new ArrayList<String>();
        locations.add("classpath:db/migration");
        if (devSeed) {
            locations.add("classpath:db/seed-dev");
        }
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations(locations.toArray(String[]::new))
                .outOfOrder(devSeed)
                .failOnMissingLocations(true);
        if (localDatabase && !devSeed) {
            configuration = configuration.ignoreMigrationPatterns("*:future", "*:missing");
        }
        return configuration.load();
    }
}
