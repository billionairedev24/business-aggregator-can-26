package ca.northline.tools.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * S-194: when the demo seed may run. Only with {@code NORTHLINE_DEMO_SEED=true} (an explicit flag the demo's seed Job
 * and {@code make demo-seed} set, never a chart default), and never where a staging or production profile or
 * environment is active ({@code spring.profiles.active} / {@code SPRING_PROFILES_ACTIVE} / {@code
 * NORTHLINE_ENVIRONMENT}): the seed writes fake businesses, people and history, and demo accounts.
 */
final class DemoGuard {

    static final String FLAG = "NORTHLINE_DEMO_SEED";

    /** Profiles and environment names the seed refuses outright. */
    static final Set<String> REFUSED = Set.of("staging", "prod", "production", "live");

    private DemoGuard() {}

    /** Why the seed may not run here, or {@code null} when it may. */
    static @Nullable String refusal(Map<Object, Object> properties, Map<String, String> env) {
        var flag = setting(properties, env, "northline.demo-seed", FLAG);
        if (!"true".equalsIgnoreCase(flag.strip())) {
            return "the demo seed runs only with " + FLAG + "=true (docs/runbooks/demo.md § One-click demo data)";
        }
        var refused = names(properties, env).stream().filter(REFUSED::contains).toList();
        if (!refused.isEmpty()) {
            return "the demo seed never runs under " + refused + ": it writes fake businesses, people and demo accounts";
        }
        return null;
    }

    /** Every active profile and the environment name, lower case. */
    static List<String> names(Map<Object, Object> properties, Map<String, String> env) {
        var all = new ArrayList<String>();
        for (var value : List.of(
                setting(properties, env, "spring.profiles.active", "SPRING_PROFILES_ACTIVE"),
                env.getOrDefault("NORTHLINE_ENVIRONMENT", ""))) {
            for (var p : value.split(",")) {
                if (!p.isBlank()) {
                    all.add(p.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        return all;
    }

    private static String setting(Map<Object, Object> properties, Map<String, String> env, String property, String variable) {
        var fromProperty = properties.get(property);
        if (fromProperty != null && !String.valueOf(fromProperty).isBlank()) {
            return String.valueOf(fromProperty);
        }
        return env.getOrDefault(variable, "");
    }
}
