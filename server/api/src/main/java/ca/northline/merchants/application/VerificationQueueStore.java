package ca.northline.merchants.application;

import ca.northline.merchants.application.VerificationQueue.Decision;
import ca.northline.merchants.application.VerificationQueue.DecisionRow;
import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Outbound port of the verification queue (S-79): applications, their checks' review facts and the decisions. */
public interface VerificationQueueStore {

    /**
     * Applications waiting for a decision (status {@code pending}), oldest submission first, then those decided since
     * {@code decidedSince}, newest decision first.
     */
    List<Application> applications(MerchantScope scope, Instant decidedSince, int limit);

    /** One business, as the queue shows it, whatever its status. */
    java.util.Optional<Application> application(String merchantId);

    /** Checklist rows with an open registry review (S-23), among these businesses. */
    Set<String> verificationsInReview(Collection<String> merchantIds);

    /** Businesses with an owner whose identity check waits for an agent (S-22 status {@code review}). */
    Set<String> identityInReview(Collection<String> merchantIds);

    void insert(String merchantId, DecisionRow decision, String role, @Nullable Instant submittedAt);

    /** A business's decisions, newest first. */
    List<DecisionRow> decisions(String merchantId);

    /** Median hours from submission to a decision, for decisions since {@code since}; null when there are none. */
    @Nullable
    Double medianDecisionHours(MerchantScope scope, Instant since);

    /**
     * @param categoryIds by id
     * @param decision the latest decision, with {@code decidedAt}
     */
    record Application(
            String merchantId,
            String displayName,
            String legalName,
            @Nullable String type,
            @Nullable String structure,
            @Nullable String province,
            @Nullable String city,
            String status,
            @Nullable Instant submittedAt,
            List<String> categoryIds,
            @Nullable Decision decision,
            @Nullable Instant decidedAt) {
        public Application {
            categoryIds = List.copyOf(categoryIds);
        }
    }
}
