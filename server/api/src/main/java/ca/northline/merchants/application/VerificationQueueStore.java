package ca.northline.merchants.application;

import ca.northline.merchants.application.VerificationQueue.Decision;
import ca.northline.merchants.application.VerificationQueue.DecisionRow;
import ca.northline.merchants.application.VerificationQueue.Recommendation;
import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port of the verification queue (S-79): applications, their checks' review facts, the stage-1 reviews
 * (four-eyes, 2026-10-18) and the decisions.
 */
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

    void insertReview(Review review, String role, @Nullable Instant submittedAt);

    /** A business's reviews, newest first. */
    List<Review> reviews(String merchantId);

    /** The current (latest open) review of each of these businesses that has one. */
    Map<String, Review> currentReviews(Collection<String> merchantIds);

    /** Closes a business's open round: its open reviews now belong to {@code decisionId}. */
    void closeRound(String merchantId, String decisionId);

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

    /** A stage-1 review as stored ({@code merchants.application_reviews}); {@code decisionId} null = open round. */
    record Review(
            String id,
            String merchantId,
            Recommendation recommendation,
            List<String> checkKeys,
            @Nullable String note,
            String reviewedBy,
            Instant reviewedAt,
            @Nullable String decisionId) {
        public Review {
            checkKeys = List.copyOf(checkKeys);
        }

        public boolean open() {
            return decisionId == null;
        }
    }
}
