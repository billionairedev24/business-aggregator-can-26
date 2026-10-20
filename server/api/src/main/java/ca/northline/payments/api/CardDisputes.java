package ca.northline.payments.api;

import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * S-156: card disputes (chargebacks Stripe told us about, S-12) for the console's agents: the issuer's deadline, the
 * Stripe dispute fee, and the evidence an agent assembles from the case (the business's response and uploads) and
 * submits to Stripe once through {@code DisputeEvidenceGateway}. Submitting moves no money: the issuer decides and
 * Stripe's {@code charge.dispute.closed} closes the case as before.
 */
public interface CardDisputes {

    /** Stripe's text evidence fields Northline fills (never the customer's name, e-mail, address or IP). */
    List<String> TEXT_FIELDS = List.of(
            "product_description",
            "service_date",
            "cancellation_policy_disclosure",
            "cancellation_rebuttal",
            "refund_policy_disclosure",
            "refund_refusal_explanation",
            "access_activity_log",
            "uncategorized_text");

    /** Stripe's file evidence fields; each takes one file of the case's evidence. */
    List<String> FILE_FIELDS = List.of(
            "receipt",
            "service_documentation",
            "customer_communication",
            "cancellation_policy",
            "refund_policy",
            "uncategorized_file");

    /** Each text answer at most this long (Stripe caps the sum of all text at 150,000 characters). */
    int TEXT_MAX = 5_000;

    String FIELD_UNKNOWN = "Pick an evidence field from the list.";
    String TEXT_TOO_LONG = "Keep each answer under 5,000 characters.";
    String FILE_UNKNOWN = "Pick a file from this case's evidence.";
    String EVIDENCE_EMPTY = "Add evidence before submitting it.";
    String NOT_CARD_DISPUTE = "This case isn't a card dispute.";
    String CLOSED = "This card dispute is closed.";
    String SUBMITTED = "The evidence was already submitted to Stripe.";
    String OVERDUE = "The issuer's deadline for evidence has passed.";
    String STRIPE_REJECTED = "Stripe didn't accept the evidence. The error is on the case; fix it and submit again.";

    Set<String> OPEN_STATUSES = Set.of("warning_needs_response", "needs_response");

    /** Open card disputes (deadline first), then those closed since {@code closedSince}. */
    List<Row> list(MerchantScope scope, Instant closedSince, int limit);

    Optional<Detail> detail(String disputeId);

    /** Saves the draft (422 per {@code fields.<name>} / {@code files.<name>}; 409 once submitted or closed). */
    Detail save(String disputeId, Draft draft, String staffId, String role);

    /**
     * Uploads the chosen files and submits the evidence to Stripe; 409 {@code evidence_submitted}, {@code dispute_closed},
     * {@code evidence_overdue}, {@code evidence_empty}, or {@code stripe_rejected} (the draft is kept with the error).
     */
    Detail submit(String disputeId, String staffId, String role);

    record Draft(Map<String, String> fields, Map<String, String> files) {
        public Draft {
            fields = Map.copyOf(fields);
            files = Map.copyOf(files);
        }
    }

    /**
     * @param dueBy the issuer's evidence deadline (Stripe {@code evidence_details.due_by}); null when Stripe didn't say
     * @param stripeStatus {@code needs_response}, {@code under_review}, {@code won}, {@code lost}, …
     * @param feeCents Stripe's dispute fee net of what was given back (Northline carries it)
     * @param decision the case's decision once the bank decided ({@code release} = won, {@code full_refund} = lost)
     */
    record Row(
            String id,
            String caseNumber,
            String merchantId,
            String subject,
            long amountCents,
            String stripeDispute,
            @Nullable String stripeStatus,
            @Nullable String stripeReason,
            @Nullable Instant dueBy,
            @Nullable Instant respondBy,
            Instant openedAt,
            @Nullable String decision,
            @Nullable Instant submittedAt,
            long feeCents) {

        public boolean open() {
            return decision == null;
        }
    }

    /**
     * @param merchantResponse what the business wrote on the case (the agent may copy it into a field)
     * @param caseEvidence the case's evidence (files the agent can attach)
     */
    record Detail(
            Row row,
            @Nullable String merchantResponse,
            List<AgentCases.EvidenceItem> caseEvidence,
            Map<String, String> fields,
            Map<String, String> files,
            @Nullable String updatedBy,
            @Nullable Instant updatedAt,
            @Nullable String submittedBy,
            @Nullable String lastError) {

        public Detail {
            caseEvidence = List.copyOf(caseEvidence);
            fields = Map.copyOf(fields);
            files = Map.copyOf(files);
        }
    }
}
