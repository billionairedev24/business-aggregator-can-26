package ca.northline.support;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * ONE PostGIS 17 container per test JVM (singleton pattern): started on first use, stopped by Ryuk when the JVM exits.
 * Every Spring test context — whatever its profiles/mocks — connects to it via {@code @ServiceConnection} in
 * {@link IntegrationTest}. Tests must therefore create their own rows (fresh ULIDs via {@link TestData}) and never
 * assume an empty table.
 */
public final class SharedPostgres {

    public static final PostgreSQLContainer INSTANCE = start();

    private SharedPostgres() {}

    private static PostgreSQLContainer start() {
        var image = DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres");
        var container = new PostgreSQLContainer(image)
                .withDatabaseName("northline")
                .withUsername("northline")
                .withPassword("northline")
                // every cached Spring test context keeps its own pool (10): leave room for all of them (S-32)
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=300");
        container.start();
        return container;
    }
}
