package ca.northline.config;

import java.util.Arrays;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * S-16: the dev seed ({@code db/seed-dev}, V1xx personas) is applied only under the {@code local} (and {@code test})
 * profile. Any other profile with a Flyway location naming {@code seed-dev} — e.g. {@code SPRING_FLYWAY_LOCATIONS} set
 * by mistake in a deployed environment — stops the api before Flyway touches the database. (The boot jar and the
 * image don't contain {@code db/seed-dev} at all; this also covers a filesystem location.)
 */
@Component
class DevSeedGuard implements FlywayConfigurationCustomizer {

    private final Environment environment;

    DevSeedGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        var seed = Arrays.stream(configuration.getLocations())
                .map(Object::toString)
                .filter(location -> location.contains("seed-dev"))
                .toList();
        if (!seed.isEmpty() && !environment.acceptsProfiles(Profiles.of("local | test"))) {
            throw new IllegalStateException("Flyway location " + seed
                    + " is the local-only dev seed; refused under profiles "
                    + Arrays.toString(environment.getActiveProfiles()) + " (docs/runbooks/deploy.md § Migrations)");
        }
    }
}
