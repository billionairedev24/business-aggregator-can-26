package ca.northline.merchants.application;

import ca.northline.merchants.domain.RegistryCheck;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound port: {@code merchants.registry_checks} and the re-check bookkeeping on {@code merchants.verifications}. */
public interface RegistryCheckStore {

    void insert(RegistryCheck check);

    /** Stores a review decision. */
    void saveReview(RegistryCheck check);

    Optional<RegistryCheck> lock(String checkId);

    /** Open manual reviews, oldest first. */
    List<RegistryCheck> openReviews(int limit);

    boolean hasOpenReview(String verificationId);

    /** Every lookup of one business that went to an agent (open or decided), newest first (S-79). */
    List<RegistryCheck> reviewsOf(String merchantId);

    /** The latest lookups of one checklist row, newest first. */
    List<RegistryCheck> latest(String verificationId, int limit);

    /** Verified rows backed by an API source whose last check is older than {@code before}; locked, skip locked. */
    List<Due> dueForRecheck(Instant before, int limit);

    void markRechecked(String verificationId, Instant at);

    record Due(String merchantId, String verificationId) {}
}
