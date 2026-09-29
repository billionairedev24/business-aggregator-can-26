package ca.northline.tools;

import java.util.ArrayList;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Database developer commands, run through Gradle (see api/build.gradle.kts):
 *
 * <pre>
 *   ./gradlew :api:flywayMigrate  [-Pdb.url=… -Pdb.user=… -Pdb.password=… -Pdb.devSeed=true]
 *   ./gradlew :api:flywayInfo
 *   ./gradlew :api:seedCategories
 * </pre>
 *
 * Uses the same classpath locations as the application ({@code db/migration}, {@code db/seed-dev}, {@code db/seed}).
 */
@Slf4j
public final class DbTool {

    private DbTool() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: DbTool migrate|info|seed-categories");
        }
        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl(System.getProperty("db.url", "jdbc:postgresql://localhost:5432/northline"));
        dataSource.setUser(System.getProperty("db.user", "northline"));
        dataSource.setPassword(System.getProperty("db.password", "northline"));

        switch (args[0]) {
            case "migrate" -> {
                var result = flyway(dataSource).migrate();
                log.info(
                        "Applied {} migration(s); schema now at {}",
                        result.migrationsExecuted,
                        result.targetSchemaVersion);
            }
            case "info" -> {
                for (var m : flyway(dataSource).info().all()) {
                    log.info("{} {} {}", m.getVersion(), m.getState(), m.getDescription());
                }
            }
            case "seed-categories" -> log.info("Upserted {} categories", new CategorySeeder(dataSource).seed());
            default -> throw new IllegalArgumentException("unknown command " + args[0]);
        }
    }

    private static Flyway flyway(PGSimpleDataSource dataSource) {
        var locations = new ArrayList<String>();
        locations.add("classpath:db/migration");
        if (Boolean.getBoolean("db.devSeed")) {
            locations.add("classpath:db/seed-dev");
        }
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(locations.toArray(String[]::new))
                .load();
    }
}
