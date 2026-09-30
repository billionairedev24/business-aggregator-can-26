package ca.northline.merchants.integration;

import ca.northline.merchants.domain.RegistryRecord.Standing;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** A source's status words → good standing ("Active", "RENEWAL LICENSED" / "Struck", "Dissolved", "EXPIRED"). */
final class RegistryStandings {
    private RegistryStandings() {}

    private static final List<String> INACTIVE = List.of(
            "inactive",
            "dissolved",
            "struck",
            "amalgamated",
            "discontinued",
            "revoked",
            "cancel",
            "expired",
            "closed",
            "suspended",
            "continued out");
    private static final List<String> ACTIVE = List.of("active", "licensed", "renewal", "pending", "good standing");

    static Standing of(@Nullable String status) {
        if (status == null || status.isBlank()) {
            return Standing.UNKNOWN;
        }
        var s = status.toLowerCase(Locale.ROOT);
        if (INACTIVE.stream().anyMatch(s::contains)) {
            return Standing.INACTIVE;
        }
        return ACTIVE.stream().anyMatch(s::contains) ? Standing.ACTIVE : Standing.UNKNOWN;
    }
}
