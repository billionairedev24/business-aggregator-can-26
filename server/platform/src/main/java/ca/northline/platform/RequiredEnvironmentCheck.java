package ca.northline.platform;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

/**
 * Fails start-up, before any bean is created, when an environment variable the active profiles need is not set.
 * Profiles list the variables under {@code northline.required-env.<purpose>} (e.g. {@code database: DB_URL, DB_USER,
 * DB_PASSWORD} in {@code application-cloud.yml}); the map merges across profile files, so a profile can add a purpose
 * or replace one. Every missing variable is reported at once, grouped by purpose. A variable counts as set when the
 * environment resolves it to a non-blank value — an OS environment variable, a system property, or a line in the
 * optional {@code .env} file a local run imports.
 */
public final class RequiredEnvironmentCheck implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String PREFIX = "northline.required-env";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        check(context.getEnvironment());
    }

    static void check(ConfigurableEnvironment environment) {
        Map<String, String[]> required = Binder.get(environment)
                .bind(PREFIX, Bindable.mapOf(String.class, String[].class))
                .orElse(Map.of());
        var missing = new TreeMap<String, List<String>>();
        required.forEach((purpose, names) -> {
            var absent = Arrays.stream(names)
                    .map(String::strip)
                    .filter(name -> !name.isEmpty())
                    .filter(name -> !StringUtils.hasText(environment.getProperty(name)))
                    .toList();
            if (!absent.isEmpty()) {
                missing.put(purpose, absent);
            }
        });
        if (!missing.isEmpty()) {
            throw new MissingEnvironmentException(List.of(environment.getActiveProfiles()), missing);
        }
    }
}
