package ca.northline.merchants.application;

import ca.northline.merchants.application.RegistryReviews.ReviewView;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.MerchantScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Platform console — the verification queue (S-79, design 03 {@code verify}): submitted applications with their
 * automated checks, the manual reviews behind them (S-23 registry lookups, S-22 identity mismatches) and the agent's
 * decision. Approving makes the business active at Registered; requesting information sends the application back to
 * the owner with the checks to redo. Every decision is audit-logged and emailed to the business's owners.
 */
public final class VerificationQueue {
    private VerificationQueue() {}

    public static final String DECISION_REQUIRED = "Choose approve or request info.";
    public static final String CHECKS_REQUIRED = "Choose what the business needs to fix.";
    public static final String UNKNOWN_CHECK = "Pick checks from this application.";
    public static final String NOTE_REQUIRED = "Tell the business what to fix.";
    public static final String TOO_LONG = "At most 500 characters.";
    public static final String REVIEW_DECISION_REQUIRED = "Choose approve or reject.";
    public static final int NOTE_MAX = 500;

    /** What an agent decided on an application ({@code merchants.application_decisions.decision}). */
    public enum Decision implements CodedEnum {
        APPROVED,
        INFO_REQUESTED
    }

    /**
     * Where one automated check stands for the agent: passed (verified), waiting for an agent's manual review (open
     * registry lookup or identity mismatch), waiting for a human to look at the evidence (uploads, numbers without an
     * API), or failed (rejected, expired, not handed in).
     */
    public enum CheckState implements CodedEnum {
        PASSED,
        REVIEW,
        WAITING,
        FAILED
    }

    /**
     * High: a licence or permit isn't confirmed (regulated work — design "High · no licence"); medium: another check
     * isn't confirmed; low: every automated check passed.
     */
    public enum Risk implements CodedEnum {
        LOW,
        MEDIUM,
        HIGH
    }

    public interface ListApplications {
        /** Decided applications stay in the queue this many days. */
        int RECENT_DAYS = 7;

        /**
         * Submitted applications in scope, oldest first, plus the ones decided in the last {@link #RECENT_DAYS} days (their
         * latest decision), and the median time from submission to a decision over the last 30 days.
         */
        Queue list(MerchantScope scope, String lang);
    }

    public interface ViewApplication {
        ApplicationDetail view(String merchantId, String lang);
    }

    public interface DecideApplication {
        /**
         * @param checkKeys checklist keys to redo ({@link Decision#INFO_REQUESTED}); ignored when approving
         * @param note to the business; required when requesting information
         * @param role the console role(s) the agent acts with, for the audit log
         */
        record Command(
                String merchantId,
                Decision decision,
                List<String> checkKeys,
                @Nullable String note,
                String agentId,
                String role) {
            public Command {
                checkKeys = List.copyOf(checkKeys);
            }
        }

        ApplicationDetail decide(DecideApplication.Command command, String lang);
    }

    public interface DecideIdentityReview {
        record Command(
                String merchantId,
                String checkId,
                boolean approve,
                @Nullable String note,
                String agentId,
                String role) {}

        ApplicationDetail decide(DecideIdentityReview.Command command, String lang);
    }

    /** @param medianDecisionHours null before the first decision of the last 30 days */
    public record Queue(
            List<ApplicationRow> items,
            long pending,
            @Nullable Double medianDecisionHours) {
        public Queue {
            items = List.copyOf(items);
        }
    }

    /**
     * One application in the queue.
     *
     * @param categories the business's categories in the requested language, in the owner's order
     * @param province two-letter code (the console names it from the region model)
     * @param status the business's status now ({@code pending} = waiting for a decision)
     * @param decision the latest decision, when there is one
     */
    public record ApplicationRow(
            String merchantId,
            String businessName,
            String legalName,
            @Nullable String type,
            @Nullable String structure,
            List<String> categories,
            @Nullable String province,
            @Nullable String city,
            String status,
            @Nullable Instant submittedAt,
            List<CheckRow> checks,
            Risk risk,
            @Nullable Decision decision,
            @Nullable Instant decidedAt) {
        public ApplicationRow {
            categories = List.copyOf(categories);
            checks = List.copyOf(checks);
        }
    }

    /** @param status {@code merchants.verifications.status} */
    public record CheckRow(
            String id,
            String key,
            String type,
            @Nullable String registry,
            String status,
            CheckState state,
            @Nullable String reference,
            @Nullable Instant expiresAt) {}

    public record ApplicationDetail(
            ApplicationRow application,
            List<OwnerReview> owners,
            List<ReviewView> registryReviews,
            List<DecisionRow> decisions) {
        public ApplicationDetail {
            owners = List.copyOf(owners);
            registryReviews = List.copyOf(registryReviews);
            decisions = List.copyOf(decisions);
        }
    }

    /**
     * An owner's Stripe Identity verification (S-22) as the agent sees it: the match results, never the document.
     *
     * @param checkId null while the owner hasn't started
     * @param status {@code not_started} or {@code owner_identity_checks.status}
     */
    public record OwnerReview(
            @Nullable String checkId,
            String principalName,
            String role,
            @Nullable BigDecimal ownershipPct,
            String status,
            @Nullable String nameMatch,
            @Nullable String dobMatch,
            @Nullable String lastError,
            int attempts,
            @Nullable String reviewNote,
            @Nullable Instant reviewedAt) {}

    public record DecisionRow(
            String id,
            Decision decision,
            List<String> checkKeys,
            @Nullable String note,
            String decidedBy,
            Instant decidedAt) {
        public DecisionRow {
            checkKeys = List.copyOf(checkKeys);
        }
    }
}
