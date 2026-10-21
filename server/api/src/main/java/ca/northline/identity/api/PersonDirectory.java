package ca.northline.identity.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Names (and the customer reliability score) of users, for merchant-facing screens. Returns only what a merchant may
 * see about a customer they serve: the display name and the reliability score. Unknown ids are absent from the map.
 */
public interface PersonDirectory {

    Map<String, Person> people(Collection<String> userIds);

    /**
     * @param noShows S-156: confirmed no-shows that put the customer under the trust rules' consequence (providers see
     *     it before accepting); 0 otherwise
     */
    record Person(String id, String displayName, @Nullable BigDecimal reliabilityScore, int noShows) {

        public Person(String id, String displayName, @Nullable BigDecimal reliabilityScore) {
            this(id, displayName, reliabilityScore, 0);
        }

        /** "Amara Osei" → "A. Osei" (lists show the short form). */
        public String shortName() {
            var parts = displayName.strip().split("\\s+");
            if (parts.length < 2) {
                return displayName.strip();
            }
            return parts[0].substring(0, 1).toUpperCase(Locale.ROOT) + ". " + parts[parts.length - 1];
        }

        public String firstName() {
            return displayName.strip().split("\\s+")[0];
        }
    }
}
