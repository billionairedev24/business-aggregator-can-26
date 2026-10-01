package ca.northline.payments.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.payments.api.AgentCases;
import ca.northline.payments.api.DisputeDecisions;
import ca.northline.payments.domain.Dispute;
import ca.northline.payments.domain.Refund;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.MerchantScope;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AgentCases} (S-80): the agents' queue and decisions. A decision is recorded ({@code agent_decisions}) and
 * audit-logged in the same transaction as the money it moves: disputes through {@link DisputeDecisions#decide} (the
 * refund queue pays approved refunds; a release lets the escrow go), refund cases through
 * {@link DisputeDecisions#decideRefund}; a goodwill credit releases the escrow to the seller and adds a Northline credit
 * for the customer, paid by the platform. Above {@link #COSIGN_ABOVE_CENTS} nothing moves until another person with the
 * {@code refund} action co-signs.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class AgentCaseService implements AgentCases {

    static final String DISPUTE = "dispute";
    static final String REFUND = "refund";
    static final String NOT_WITH_AGENT = "This case isn't waiting for an agent.";
    static final String AWAITING_COSIGN = "This case already has a decision waiting for a finance co-sign.";
    static final String COSIGN_CLOSED = "This decision was already co-signed or declined.";

    private final AgentCaseStore store;
    private final CaseRepository cases;
    private final EscrowRepository escrows;
    private final DisputeDecisions decisions;
    private final DisputeEvidenceStorage storage;
    private final AuditTrail audit;
    private final Clock clock;

    @Override
    public Summary summary(MerchantScope scope, Instant weekAgo) {
        return store.summary(scope, weekAgo);
    }

    @Override
    public List<CaseRow> queue(MerchantScope scope, Instant decidedSince, int limit) {
        return store.queue(scope, decidedSince, limit).stream()
                .map(ref -> row(ref.kind(), ref.id()))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public Optional<CaseDetail> detail(String kind, String caseId) {
        var row = row(kind, caseId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        if (REFUND.equals(kind)) {
            return Optional.of(new CaseDetail(row.get(), List.of(), 0, 0, 0, null, null));
        }
        var dispute = cases.dispute(caseId).orElseThrow();
        var seller = store.merchantDisputes(dispute.getMerchantId(), caseId);
        var evidence = dispute.getEvidence().stream()
                .map(e -> new EvidenceItem(
                        e.id(),
                        e.kind().code(),
                        e.name(),
                        e.contentType(),
                        e.size(),
                        e.by(),
                        e.at(),
                        e.storageKey() != null))
                .toList();
        return Optional.of(new CaseDetail(
                row.get(),
                evidence,
                dispute.getOpenedBy() == null ? 0 : store.customerDisputes(dispute.getOpenedBy(), caseId),
                seller[0],
                seller[1],
                dispute.getOfferCents(),
                dispute.getOfferState() == null ? null : dispute.getOfferState().code()));
    }

    @Override
    @Transactional
    public CaseRow decide(Decide c) {
        var merchantId = "";
        long returned;
        switch (c.kind()) {
            case DISPUTE -> {
                var dispute = cases.dispute(c.caseId()).orElseThrow(() -> new NotFound("dispute", c.caseId()));
                if (dispute.getState() != Dispute.State.AGENT && dispute.getState() != Dispute.State.APPEALED) {
                    throw new Conflict("not_with_agent", NOT_WITH_AGENT);
                }
                merchantId = dispute.getMerchantId();
                returned = switch (c.outcome()) {
                    case FULL_REFUND -> dispute.getAmountCents();
                    case RELEASE -> 0L;
                    case PARTIAL -> {
                        if (c.refundCents() <= 0 || c.refundCents() >= dispute.getAmountCents()) {
                            throw RuleViolation.of("refundCents", "range", PARTIAL_RANGE);
                        }
                        yield c.refundCents();
                    }
                    case GOODWILL_CREDIT -> {
                        if (c.refundCents() <= 0 || c.refundCents() > dispute.getAmountCents()) {
                            throw RuleViolation.of("refundCents", "range", PARTIAL_RANGE);
                        }
                        yield c.refundCents();
                    }
                };
            }
            case REFUND -> {
                var refund = cases.refund(c.caseId()).orElseThrow(() -> new NotFound("refund", c.caseId()));
                if (refund.getState() != Refund.State.AGENT_REVIEW) {
                    throw new Conflict("not_with_agent", NOT_WITH_AGENT);
                }
                if (c.outcome() != Outcome.FULL_REFUND && c.outcome() != Outcome.RELEASE) {
                    throw RuleViolation.of("outcome", "option", REFUND_OUTCOMES);
                }
                merchantId = refund.getMerchantId();
                returned = c.outcome() == Outcome.FULL_REFUND ? refund.getAmountCents() : 0L;
            }
            default -> throw new NotFound("case", c.caseId());
        }
        if (store.pending(c.kind(), c.caseId()).isPresent()) {
            throw new Conflict("awaiting_cosign", AWAITING_COSIGN);
        }
        var now = clock.instant();
        var needsCosign = returned > COSIGN_ABOVE_CENTS;
        var note = blankToNull(c.note());
        var decision = new AgentDecision(
                Ids.next(),
                c.outcome().code(),
                returned,
                note,
                c.staffId(),
                now,
                needsCosign ? "awaiting_cosign" : "applied",
                null,
                null,
                null);
        store.insert(c.kind(), c.caseId(), merchantId, decision, c.role());
        audit.record(AuditTrail.Entry.of(
                        merchantId,
                        c.staffId(),
                        c.role(),
                        needsCosign ? "disputes.decision_awaiting_cosign" : "disputes.decided",
                        c.kind(),
                        c.caseId())
                .withChange(
                        null,
                        Map.of("outcome", c.outcome().code(), "refundCents", returned, "decision", decision.id())));
        if (!needsCosign) {
            apply(c.kind(), c.caseId(), c.outcome(), returned, c.staffId(), note);
        }
        return row(c.kind(), c.caseId()).orElseThrow();
    }

    @Override
    @Transactional
    public CaseRow cosign(Cosign c) {
        var stored = store.find(c.decisionId()).orElseThrow(() -> new NotFound("decision", c.decisionId()));
        var d = stored.decision();
        if (!"awaiting_cosign".equals(d.state())) {
            throw new Conflict("cosign_closed", COSIGN_CLOSED);
        }
        if (d.decidedBy().equals(c.staffId())) {
            throw new Conflict("cosign_self", COSIGN_SELF);
        }
        var now = clock.instant();
        var note = blankToNull(c.note());
        if (!store.cosign(d.id(), c.approve() ? "applied" : "declined", c.staffId(), c.role(), note, now)) {
            throw new Conflict("cosign_closed", COSIGN_CLOSED);
        }
        audit.record(AuditTrail.Entry.of(
                        stored.merchantId(),
                        c.staffId(),
                        c.role(),
                        c.approve() ? "disputes.cosigned" : "disputes.cosign_declined",
                        stored.kind(),
                        stored.caseId())
                .withChange(
                        Map.of("decision", d.id(), "state", d.state()),
                        Map.of("state", c.approve() ? "applied" : "declined")));
        if (c.approve()) {
            apply(
                    stored.kind(),
                    stored.caseId(),
                    Outcome.valueOf(d.outcome().toUpperCase(java.util.Locale.ROOT)),
                    d.refundCents(),
                    d.decidedBy(),
                    d.note());
        }
        return row(stored.kind(), stored.caseId()).orElseThrow();
    }

    @Override
    public Optional<EvidenceFile> evidence(String disputeId, String evidenceId) {
        return cases.dispute(disputeId)
                .flatMap(d -> d.getEvidence().stream()
                        .filter(e -> e.id().equals(evidenceId))
                        .findFirst())
                .flatMap(e -> e.storageKey() == null
                        ? Optional.empty()
                        : storage.get(e.storageKey()).map(f -> new EvidenceFile(f.bytes(), f.contentType(), e.name())));
    }

    /** Moves the money for an agent's decision (the decider is the agent of record, whoever co-signed). */
    private void apply(
            String kind, String caseId, Outcome outcome, long returned, String agentId, @Nullable String note) {
        if (REFUND.equals(kind)) {
            decisions.decideRefund(caseId, outcome == Outcome.FULL_REFUND, agentId);
            return;
        }
        if (outcome == Outcome.GOODWILL_CREDIT) {
            decisions.decide(caseId, DisputeDecisions.Decision.RELEASE, 0, agentId, note);
            var dispute = cases.dispute(caseId).orElseThrow();
            var escrow = escrows.findById(dispute.getEscrowId())
                    .orElseThrow(() -> new NotFound("escrow", dispute.getEscrowId()));
            cases.insert(Refund.goodwillCredit(cases.nextCaseNumber("RF"), dispute, escrow, returned, clock.instant()));
            return;
        }
        var decision = switch (outcome) {
            case FULL_REFUND -> DisputeDecisions.Decision.FULL_REFUND;
            case PARTIAL -> DisputeDecisions.Decision.PARTIAL;
            case RELEASE, GOODWILL_CREDIT -> DisputeDecisions.Decision.RELEASE;
        };
        decisions.decide(caseId, decision, returned, agentId, note);
    }

    private Optional<CaseRow> row(String kind, String caseId) {
        var pending = store.pending(kind, caseId).orElse(null);
        var applied = store.applied(kind, caseId).orElse(null);
        if (DISPUTE.equals(kind)) {
            return cases.dispute(caseId)
                    .map(d -> new CaseRow(
                            DISPUTE,
                            d.getId(),
                            d.getCaseNumber(),
                            d.getMerchantId(),
                            d.getSubject(),
                            d.getAmountCents(),
                            d.getCustomerName(),
                            d.getCustomerStatement(),
                            d.getResponse(),
                            pending != null ? "awaiting_cosign" : stateOf(d.getState()),
                            d.getOpenedAt(),
                            pending,
                            applied));
        }
        if (REFUND.equals(kind)) {
            return cases.refund(caseId)
                    .map(r -> new CaseRow(
                            REFUND,
                            r.getId(),
                            r.getCaseNumber(),
                            r.getMerchantId(),
                            r.getWhat(),
                            r.getAmountCents(),
                            r.getCustomerName(),
                            null,
                            r.getContestReason(),
                            pending != null
                                    ? "awaiting_cosign"
                                    : r.getState() == Refund.State.AGENT_REVIEW
                                            ? "agent"
                                            : r.getState().code(),
                            r.getCreatedAt(),
                            pending,
                            applied));
        }
        return Optional.empty();
    }

    private static String stateOf(Dispute.State state) {
        return switch (state) {
            case AGENT, APPEALED -> "agent";
            case DECIDED -> "decided";
            case OPEN, SELLER_REPLIED -> state.code();
        };
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
