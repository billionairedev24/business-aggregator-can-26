package ca.northline.merchants.application;

import ca.northline.merchants.api.RestrictedLicences.Licence;
import ca.northline.region.api.AgeClass;
import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** {@code merchants.restricted_licences} (V342). */
public interface RestrictedLicenceStore {

    void insert(Licence licence, String submittedBy);

    Optional<Licence> lock(String licenceId);

    Optional<QueueRow> row(String licenceId);

    List<Licence> of(String merchantId);

    /** Approved, unexpired on {@code today}, in the business's current province. */
    Set<AgeClass> licensedClasses(String merchantId, LocalDate today);

    void decide(
            String licenceId,
            String status,
            String staffId,
            Instant at,
            @Nullable String reason,
            @Nullable String note);

    /** Approved licences of the class other than {@code keep} become {@code replaced}; returns how many. */
    int replaceOthers(String merchantId, AgeClass ageClass, String keep);

    /** Approved licences whose expiry is before {@code today}, locked; then marked expired by {@link #expire}. */
    List<Licence> dueToExpire(LocalDate today, int limit);

    void expire(String licenceId);

    /** Approved licences expiring on or before {@code by} not reminded yet. */
    List<Licence> dueForReminder(LocalDate by, int limit);

    void reminded(String licenceId, Instant at);

    /** The console queue: pending first, then the latest decisions. */
    List<QueueRow> queue(MerchantScope scope, @Nullable String status, int limit);

    @Nullable
    String province(String merchantId);

    record QueueRow(
            Licence licence, String businessName, @Nullable String businessProvince) {}
}
