package ca.northline.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** S-16: db/seed-dev locations are refused outside the local and test profiles. */
class DevSeedGuardTest {

    private static void customize(String profiles, String... locations) {
        var environment = new MockEnvironment();
        if (!profiles.isEmpty()) {
            environment.setActiveProfiles(profiles.split(","));
        }
        new DevSeedGuard(environment).customize(Flyway.configure().locations(locations));
    }

    @Test
    void seedAllowedUnderLocalAndTest() {
        assertThatCode(() -> customize("local", "classpath:db/migration", "classpath:db/seed-dev"))
                .doesNotThrowAnyException();
        assertThatCode(() -> customize("test", "classpath:db/migration", "classpath:db/seed-dev"))
                .doesNotThrowAnyException();
        assertThatCode(() -> customize("local,valkey", "classpath:db/seed-dev")).doesNotThrowAnyException();
    }

    @Test
    void seedRefusedUnderDeployedProfiles() {
        for (var profiles : new String[] {"dev,cloud", "staging,cloud", "prod,cloud", ""}) {
            assertThatThrownBy(() -> customize(profiles, "classpath:db/migration", "classpath:db/seed-dev"))
                    .as(profiles)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("local-only dev seed");
            assertThatThrownBy(() -> customize(profiles, "filesystem:/opt/db/seed-dev"))
                    .as(profiles)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void migrationsAloneAreFineEverywhere() {
        for (var profiles : new String[] {"dev,cloud", "staging,cloud", "prod,cloud", "local", ""}) {
            assertThatCode(() -> customize(profiles, "classpath:db/migration")).doesNotThrowAnyException();
        }
    }
}
