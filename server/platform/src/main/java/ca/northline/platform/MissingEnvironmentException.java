package ca.northline.platform;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Start-up failure: the active profiles need environment variables that are not set (purpose → variable names). */
public final class MissingEnvironmentException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final List<String> profiles;
    private final Map<String, List<String>> missing;

    MissingEnvironmentException(List<String> profiles, Map<String, List<String>> missing) {
        super("Missing required environment variables for profiles " + profiles + ": " + listing(missing, ", "));
        this.profiles = List.copyOf(profiles);
        this.missing = Map.copyOf(missing);
    }

    public List<String> profiles() {
        return profiles;
    }

    public Map<String, List<String>> missing() {
        return missing;
    }

    static String listing(Map<String, List<String>> missing, String separator) {
        return missing.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + ": " + String.join(", ", e.getValue()))
                .collect(Collectors.joining(separator));
    }
}
