package ca.northline.merchants.api;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Read-only facts about a business for other modules (S-37): the only way to read {@code merchants.merchants} outside
 * the merchants module. Codes are the lower-case column values ({@code provider|seller|kitchen|both},
 * {@code registered|trusted|master}, {@code draft|submitted|active|…}).
 */
public interface MerchantDirectory {

    Optional<MerchantProfile> profile(String merchantId);

    /** The profiles of many businesses at once, by id; unknown ids are left out. */
    default Map<String, MerchantProfile> profiles(Collection<String> merchantIds) {
        var found = new LinkedHashMap<String, MerchantProfile>();
        merchantIds.forEach(id -> profile(id).ifPresent(p -> found.put(id, p)));
        return found;
    }

    /**
     * @param takeRateBps the business's own take rate, or null for its tier's default
     * @param province two-letter code where the business operates, or null before onboarding sets it
     * @param city the city the business trades in (its market), or null when not known
     */
    record MerchantProfile(
            String merchantId,
            String type,
            String tier,
            String status,
            @Nullable Integer takeRateBps,
            @Nullable String province,
            @Nullable String city) {

        /** Approved: the business may trade. */
        public boolean active() {
            return "active".equals(status);
        }
    }
}
