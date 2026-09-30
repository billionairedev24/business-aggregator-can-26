package ca.northline.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S-16: the dev seed can't be applied outside a local run, and it is not packaged with the application (so neither the
 * boot jar nor the image carries it).
 */
class DbToolTest {

    @Test
    void devSeedAllowedOnlyForLocalRuns() {
        assertThat(DbTool.devSeedRefusal(List.of(), "jdbc:postgresql://localhost:5432/northline"))
                .isNull();
        assertThat(DbTool.devSeedRefusal(List.of("local"), "jdbc:postgresql://127.0.0.1:55432/northline"))
                .isNull();
        assertThat(DbTool.devSeedRefusal(List.of(), "jdbc:postgresql://postgres:5432/northline"))
                .isNull();
        assertThat(DbTool.devSeedRefusal(List.of(), "jdbc:postgresql://[::1]:5432/northline"))
                .isNull();
    }

    @Test
    void devSeedRefusedUnderDeployedProfiles() {
        for (var profile : List.of("dev", "staging", "prod", "cloud")) {
            assertThat(DbTool.devSeedRefusal(List.of(profile), "jdbc:postgresql://localhost:5432/northline"))
                    .as(profile)
                    .contains("local-only");
        }
    }

    @Test
    void devSeedRefusedForRemoteDatabases() {
        assertThat(DbTool.devSeedRefusal(
                        List.of(),
                        "jdbc:postgresql://northline-prod.abc.ca-central-1.rds.amazonaws.com:5432/northline"))
                .contains("refusing to apply it to northline-prod.abc.ca-central-1.rds.amazonaws.com");
        assertThat(DbTool.devSeedRefusal(List.of(), "jdbc:postgresql://10.20.0.5:5432/northline?sslmode=require"))
                .contains("10.20.0.5");
    }

    @Test
    void profilesComeFromPropertyOrEnvironment() {
        assertThat(DbTool.profiles(
                        Map.of("spring.profiles.active", "Local"), Map.of("SPRING_PROFILES_ACTIVE", "prod,cloud")))
                .containsExactly("local", "prod", "cloud");
        assertThat(DbTool.profiles(Map.of(), Map.of("NORTHLINE_ENVIRONMENT", "staging")))
                .containsExactly("staging");
    }

    @Test
    void migrateWithDevSeedUnderProdStopsBeforeConnecting() {
        assertThatThrownBy(() -> DbTool.run(
                        "migrate",
                        Map.of("db.devSeed", "true", "db.url", "jdbc:postgresql://localhost:1/none"),
                        Map.of("SPRING_PROFILES_ACTIVE", "prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never applied under [prod]");
    }

    @Test
    void devSeedIsNotAMainResource() {
        // Gradle runs tests from the project directory: build/resources/main is what the boot jar and the image get.
        assertThat(Path.of("build/resources/main/db/migration")).isDirectory();
        assertThat(Path.of("build/resources/main/db/seed-dev")).doesNotExist();
        // … while the tests and bootRun still see it on their own classpath entry.
        var seed = getClass().getResource("/db/seed-dev/V100__dev_personas.sql");
        assertThat(seed).isNotNull();
        assertThat(seed.toString()).contains("/build/dev-seed/");
        assertThat(Files.exists(Path.of("build/dev-seed/db/seed-dev/V100__dev_personas.sql")))
                .isTrue();
    }
}
