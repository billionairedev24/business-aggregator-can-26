package ca.northline.restricted.api;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** A customer's age check (done once; kept as "verified over N, on date, by method" only). */
public interface AgeVerifications {

    Status status(String userId);

    /** Is the customer verified as at least {@code minimumAge} today? */
    default boolean verifiedFor(String userId, int minimumAge) {
        return status(userId).ageFloor() >= minimumAge;
    }

    /**
     * @param state {@code none | pending | verified | failed}
     * @param overAge the age verified on {@code verifiedOn} (capped at the strictest age the region model asks)
     * @param ageFloor what the customer is at least today: {@code overAge} plus the whole years since; 0 when not
     *     verified
     * @param method {@code stripe_identity_document_selfie | fake}
     * @param lastError the provider's code of a failed attempt ({@code document_expired}, {@code under_age}, …)
     */
    record Status(
            String state,
            @Nullable Integer overAge,
            @Nullable LocalDate verifiedOn,
            int ageFloor,
            @Nullable String method,
            @Nullable String lastError) {

        public static final String NONE = "none";
        public static final String PENDING = "pending";
        public static final String VERIFIED = "verified";
        public static final String FAILED = "failed";

        public static Status none() {
            return new Status(NONE, null, null, 0, null, null);
        }
    }
}
