package ca.northline.auth.support;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** One PostGIS 17 container per test JVM (same image as the api tests); Flyway applies db/migrations. */
public final class SharedPostgres {

    public static final PostgreSQLContainer INSTANCE = start();

    private SharedPostgres() {}

    private static PostgreSQLContainer start() {
        var image = DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres");
        var container = new PostgreSQLContainer(image)
                .withDatabaseName("northline")
                .withUsername("northline")
                .withPassword("northline");
        container.start();
        return container;
    }
}
