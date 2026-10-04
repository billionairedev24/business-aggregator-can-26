package ca.northline.merchants.api;

import ca.northline.region.api.AgeClass;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Age-restricted purchases (2026-10-04): a business lists products of an {@link AgeClass} only while it holds an
 * approved, unexpired licence for that class in its province (a liquor licence, a tobacco/vape retail permit). The
 * catalogue and food modules ask here before publishing and hide restricted listings on {@link
 * RestrictedLicenceChanged}; the console's vetting queue reviews submitted licences.
 */
public interface RestrictedLicences {

    /** The classes the business is licensed for today (approved, unexpired, its current province). */
    Set<AgeClass> licensedClasses(String merchantId);

    default boolean licensed(String merchantId, AgeClass ageClass) {
        return licensedClasses(merchantId).contains(ageClass);
    }

    /** The business's licences, newest first (Studio, seller detail). */
    List<Licence> of(String merchantId);

    /**
     * @param status {@code pending | approved | rejected | expired | replaced}
     * @param rejectReason {@code unreadable | wrong_class | wrong_business | expired | not_valid | other}
     */
    record Licence(
            String id,
            String merchantId,
            AgeClass ageClass,
            String province,
            String licenceNumber,
            String documentId,
            LocalDate expiresOn,
            String status,
            Instant submittedAt,
            @Nullable Instant decidedAt,
            @Nullable String rejectReason,
            @Nullable String note) {}
}
