package ca.northline.merchants.domain;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * A public registry record as a source returned it: the business's name, its number, whether it is in good standing and
 * when it expires (licences). Public business data — no personal information is read from any registry.
 *
 * @param rawStatus the source's own words ("Active", "RENEWAL LICENSED", "Dissolved") for the evidence
 */
public record RegistryRecord(
        String name,
        String number,
        Standing standing,
        @Nullable String rawStatus,
        @Nullable LocalDate expiresOn) {

    /** Good standing as far as the source says. */
    public enum Standing {
        ACTIVE,
        INACTIVE,
        UNKNOWN
    }
}
