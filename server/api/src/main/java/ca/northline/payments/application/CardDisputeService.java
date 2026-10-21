package ca.northline.payments.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.payments.api.AgentCases.EvidenceItem;
import ca.northline.payments.api.CardDisputes;
import ca.northline.payments.domain.Dispute;
import ca.northline.payments.domain.LedgerEntry;
import ca.northline.shared.Conflict;
import ca.northline.shared.MerchantScope;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CardDisputes} (S-156): the console's card-dispute desk. Evidence is a draft until an agent submits it; the
 * submission uploads each chosen file of the case (the business's uploads) to Stripe once and sets the dispute's
 * evidence with {@code submit=true}. A refusal from Stripe keeps the draft and records the error (409
 * {@code stripe_rejected}); nothing is submitted twice (idempotency keys per dispute and file). Also records what the
 * webhooks say about a card dispute that the case itself doesn't keep: the issuer's deadline and Stripe's fee.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class CardDisputeService implements CardDisputes {

    private final CardDisputeStore store;
    private final CaseRepository cases;
    private final DisputeEvidenceStorage files;
    private final DisputeEvidenceGateway gateway;
    private final LedgerRepository ledger;
    private final AuditTrail audit;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<Row> list(MerchantScope scope, Instant closedSince, int limit) {
        return store.list(scope, closedSince, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Detail> detail(String disputeId) {
        return store.row(disputeId)
                .map(row -> detail(row, store.evidence(disputeId).orElse(CardDisputeStore.Evidence.EMPTY)));
    }

    @Override
    public Detail save(String disputeId, Draft draft, String staffId, String role) {
        var dispute = cardDispute(disputeId);
        var current = store.lockEvidence(disputeId);
        requireEditable(dispute, current);
        var clean = validate(dispute, draft);
        store.saveDraft(disputeId, clean.fields(), clean.files(), staffId, clock.instant());
        return required(disputeId);
    }

    @Override
    @Transactional(noRollbackFor = Conflict.class)
    public Detail submit(String disputeId, String staffId, String role) {
        var dispute = cardDispute(disputeId);
        var draft = store.lockEvidence(disputeId);
        requireEditable(dispute, draft);
        var row = store.row(disputeId).orElseThrow();
        var now = clock.instant();
        if (row.dueBy() != null && row.dueBy().isBefore(now)) {
            throw new Conflict("evidence_overdue", OVERDUE);
        }
        var clean = validate(dispute, new Draft(draft.fields(), draft.files()));
        if (clean.fields().isEmpty() && clean.files().isEmpty()) {
            throw new Conflict("evidence_empty", EVIDENCE_EMPTY);
        }
        var stripeDispute = Objects.requireNonNull(dispute.getStripeDispute());
        var evidence = new LinkedHashMap<String, String>(clean.fields());
        var uploaded = new LinkedHashMap<String, String>();
        try {
            clean.files().forEach((field, evidenceId) -> {
                var item = dispute.getEvidence().stream()
                        .filter(e -> e.id().equals(evidenceId))
                        .findFirst()
                        .orElseThrow();
                var stored = files.get(Objects.requireNonNull(item.storageKey()))
                        .orElseThrow(() -> new IllegalStateException("evidence file " + evidenceId + " is gone"));
                var fileId = gateway.upload(
                        item.name(),
                        stored.contentType(),
                        stored.bytes(),
                        StripeIdempotencyKeys.of("dispute-evidence-file", disputeId, evidenceId));
                uploaded.put(field, fileId);
                evidence.put(field, fileId);
            });
            var result = gateway.submit(
                    stripeDispute, evidence, StripeIdempotencyKeys.of("dispute-evidence", disputeId, stripeDispute));
            store.dueBy(disputeId, result.dueBy());
        } catch (RuntimeException e) {
            log.warn("Submitting evidence of card dispute {} to {} failed", disputeId, gateway.provider(), e);
            var message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            store.failed(disputeId, message);
            throw new Conflict("stripe_rejected", STRIPE_REJECTED);
        }
        store.submitted(disputeId, uploaded, staffId, role, now);
        audit.record(new AuditTrail.Entry(
                dispute.getMerchantId(),
                staffId,
                role,
                "disputes.evidence_submitted",
                "dispute",
                disputeId,
                null,
                Map.of("fields", List.copyOf(evidence.keySet()), "provider", gateway.provider())));
        return required(disputeId);
    }

    /**
     * From a {@code charge.dispute.*} webhook: the issuer's deadline and each balance transaction's fee (once per
     * transaction: the posting's idempotency key). Northline carries the fee — {@code dispute_fees} against the Stripe
     * balance; a fee Stripe gives back (negative) reverses it.
     */
    void recordFromWebhook(String disputeId, StripeObject dispute, Instant now) {
        store.dueBy(disputeId, dispute.time("evidence_details", "due_by"));
        for (var txn : dispute.objects("balance_transactions")) {
            var id = txn.id();
            var fee = txn.number("fee");
            if (id == null || fee == null || fee == 0) {
                continue;
            }
            var at = Objects.requireNonNullElse(txn.time("created"), now);
            if (store.fee(id, disputeId, fee, at)) {
                ledger.post(LedgerEntry.disputeFee(id, fee, at));
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────

    private Detail required(String disputeId) {
        return detail(disputeId).orElseThrow(() -> new NotFound("dispute", disputeId));
    }

    private Detail detail(Row row, CardDisputeStore.Evidence evidence) {
        var dispute = cases.dispute(row.id()).orElseThrow();
        return new Detail(
                row,
                dispute.getResponse(),
                dispute.getEvidence().stream()
                        .map(e -> new EvidenceItem(
                                e.id(),
                                e.kind().code(),
                                e.name(),
                                e.contentType(),
                                e.size(),
                                e.by(),
                                e.at(),
                                e.storageKey() != null))
                        .toList(),
                evidence.fields(),
                evidence.files(),
                evidence.updatedBy(),
                evidence.updatedAt(),
                evidence.submittedBy(),
                evidence.lastError());
    }

    private Dispute cardDispute(String disputeId) {
        var dispute = cases.dispute(disputeId).orElseThrow(() -> new NotFound("dispute", disputeId));
        if (!dispute.isChargeback()) {
            throw new Conflict("not_card_dispute", NOT_CARD_DISPUTE);
        }
        return dispute;
    }

    private static void requireEditable(Dispute dispute, CardDisputeStore.Evidence evidence) {
        if (dispute.decided()) {
            throw new Conflict("dispute_closed", CLOSED);
        }
        var status = dispute.getStripeStatus();
        if (evidence.submittedAt() != null || (status != null && !OPEN_STATUSES.contains(status))) {
            throw new Conflict("evidence_submitted", SUBMITTED);
        }
    }

    /** Known fields only, trimmed, blank ones dropped; files must be stored evidence of this case. */
    private static Draft validate(Dispute dispute, Draft draft) {
        var problems = new ArrayList<Violation>();
        var fields = new LinkedHashMap<String, String>();
        draft.fields().forEach((name, value) -> {
            var path = "fields." + name;
            if (!TEXT_FIELDS.contains(name)) {
                problems.add(new Violation(path, "allowed", FIELD_UNKNOWN));
                return;
            }
            var text = value.strip();
            if (text.length() > TEXT_MAX) {
                problems.add(new Violation(path, "length", TEXT_TOO_LONG));
            } else if (!text.isEmpty()) {
                fields.put(name, text);
            }
        });
        var chosen = new LinkedHashMap<String, String>();
        draft.files().forEach((name, evidenceId) -> {
            var path = "files." + name;
            if (!FILE_FIELDS.contains(name)) {
                problems.add(new Violation(path, "allowed", FIELD_UNKNOWN));
                return;
            }
            if (evidenceId.isBlank()) {
                return;
            }
            var stored =
                    dispute.getEvidence().stream().anyMatch(e -> e.id().equals(evidenceId) && e.storageKey() != null);
            if (!stored) {
                problems.add(new Violation(path, "allowed", FILE_UNKNOWN));
            } else {
                chosen.put(name, evidenceId);
            }
        });
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }
        return new Draft(fields, chosen);
    }
}
