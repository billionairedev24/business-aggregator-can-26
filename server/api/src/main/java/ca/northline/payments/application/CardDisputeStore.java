package ca.northline.payments.application;

import ca.northline.payments.api.CardDisputes.Row;
import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-156): card disputes' console rows, the evidence draft ({@code payments.card_dispute_evidence}), the
 * issuer's deadline and Stripe's dispute fees ({@code payments.dispute_fees}).
 */
public interface CardDisputeStore {

    List<Row> list(MerchantScope scope, Instant closedSince, int limit);

    Optional<Row> row(String disputeId);

    Optional<Evidence> evidence(String disputeId);

    /** Locks the draft row (creates it empty first) for a save or a submission. */
    Evidence lockEvidence(String disputeId);

    void saveDraft(String disputeId, Map<String, String> fields, Map<String, String> files, String staffId, Instant at);

    void submitted(String disputeId, Map<String, String> stripeFiles, String staffId, String role, Instant at);

    void failed(String disputeId, String error);

    /** Stripe's {@code evidence_details.due_by} as last reported. */
    void dueBy(String disputeId, @Nullable Instant dueBy);

    /** Records one balance transaction's fee; false when it was already recorded (its ledger posting too). */
    boolean fee(String balanceTransaction, String disputeId, long feeCents, Instant at);

    record Evidence(
            Map<String, String> fields,
            Map<String, String> files,
            @Nullable String updatedBy,
            @Nullable Instant updatedAt,
            @Nullable Instant submittedAt,
            @Nullable String submittedBy,
            @Nullable String lastError) {

        public static final Evidence EMPTY = new Evidence(Map.of(), Map.of(), null, null, null, null, null);

        public Evidence {
            fields = Map.copyOf(fields);
            files = Map.copyOf(files);
        }
    }
}
