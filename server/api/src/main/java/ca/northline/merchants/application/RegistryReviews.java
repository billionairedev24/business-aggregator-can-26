package ca.northline.merchants.application;

import ca.northline.merchants.domain.RegistryCheck;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Platform console (staff): the registry lookups that didn't match, and the agent's decision (S-23). */
public final class RegistryReviews {
    private RegistryReviews() {}

    public static final String DECISION_REQUIRED = "Choose approve or reject.";
    public static final String NOTHING_TO_LOOK_UP = "There is no number to look up for this check.";

    /**
     * S-158: the console's registry check queue runs a lookup again (the source was down, or the owner fixed the name
     * at the registry). The open reviews of that checklist row are closed as {@code superseded}; all matched → the
     * row is verified, otherwise the new unmatched lookups wait for an agent.
     */
    public interface RecheckReview {

        /**
         * @param verificationStatus the checklist row afterwards ({@code verified} | {@code submitted} | …)
         * @param checks the new lookups (with their open reviews when unmatched)
         */
        record Rechecked(String verificationStatus, List<ReviewView> checks) {}

        Rechecked recheck(String checkId, String agentId, String role);
    }

    public interface ListReviews {
        List<ReviewView> open(int limit);
    }

    public interface DecideReview {
        /**
         * @param reference what the agent checked (registry-agent search number, licence number); approve only
         * @param expiresOn the licence's expiry the agent saw; approve only
         * @param role the console role(s) the agent acts with, for the audit log (S-79)
         */
        record Command(
                String checkId,
                boolean approve,
                String agentId,
                String role,
                @Nullable String note,
                @Nullable String reference,
                @Nullable LocalDate expiresOn) {}

        ReviewView decide(Command command);
    }

    /** One lookup waiting for (or decided by) an agent, with the evidence. */
    public record ReviewView(
            String id,
            String merchantId,
            String businessName,
            String checkKey,
            String source,
            String subject,
            @Nullable String registry,
            String queryNumber,
            @Nullable String expectedName,
            String outcome,
            List<String> reasons,
            @Nullable String recordName,
            @Nullable String recordNumber,
            @Nullable String recordStatus,
            @Nullable LocalDate recordExpiresOn,
            @Nullable String reference,
            java.time.Instant checkedAt,
            @Nullable String reviewState,
            @Nullable String reviewNote) {

        public static ReviewView of(RegistryCheck c, String businessName, String checkKey) {
            return new ReviewView(
                    c.getId(),
                    c.getMerchantId(),
                    businessName,
                    checkKey,
                    c.getSource().code(),
                    c.getSubject().code(),
                    c.getRegistry(),
                    c.getQueryNumber(),
                    c.getExpectedName(),
                    c.getOutcome().code(),
                    c.getReasons(),
                    c.getRecordName(),
                    c.getRecordNumber(),
                    c.getRecordStatus(),
                    c.getRecordExpiresOn(),
                    c.getReference(),
                    c.getCheckedAt(),
                    c.getReviewState() == null ? null : c.getReviewState().code(),
                    c.getReviewNote());
        }
    }
}
