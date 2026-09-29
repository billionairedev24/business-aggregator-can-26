package ca.northline.payments.web;

import ca.northline.payments.application.EarningsReadModel;
import ca.northline.payments.application.PayoutGateway;
import ca.northline.payments.application.RespondToCases;
import ca.northline.payments.application.ViewEarnings;
import ca.northline.payments.application.ViewPayouts;
import ca.northline.payments.application.ViewSalesReport;
import ca.northline.payments.domain.Dispute;
import ca.northline.payments.domain.Evidence;
import ca.northline.payments.domain.Fees;
import ca.northline.payments.domain.Payout;
import ca.northline.payments.domain.PayoutAccount;
import ca.northline.payments.domain.PayoutSchedule;
import ca.northline.payments.domain.Refund;
import ca.northline.payments.domain.Tier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Domain / read models → JSON records of the Finance screens. */
@Mapper
interface PaymentsWebMapper {

    @Mapping(target = "id", source = "escrowId")
    EarningsResponses.LedgerLine toResponse(EarningsReadModel.LedgerLine line);

    List<EarningsResponses.LedgerLine> toLedger(List<EarningsReadModel.LedgerLine> lines);

    EarningsResponses.Report toResponse(ViewSalesReport.Report report);

    @Mapping(target = "label", expression = "java(account.label())")
    PayoutResponses.Account toResponse(PayoutAccount account);

    @Mapping(target = "reference", source = "stripePayout")
    @Mapping(target = "netCents", expression = "java(payout.netCents())")
    PayoutResponses.PayoutLine toResponse(Payout payout);

    List<PayoutResponses.PayoutLine> toPayouts(List<Payout> payouts);

    PayoutResponses.Schedule toResponse(PayoutSchedule schedule);

    PayoutResponses.Preview toResponse(ViewPayouts.Preview preview);

    PayoutResponses.LinkSession toResponse(PayoutGateway.LinkSession session);

    @Mapping(target = "downloadable", expression = "java(evidence.storageKey() != null)")
    CaseResponses.EvidenceItem toResponse(Evidence evidence);

    List<CaseResponses.EvidenceItem> toEvidence(List<Evidence> evidence);

    default EarningsResponses.Overview toResponse(ViewEarnings.Overview o) {
        var rates = new LinkedHashMap<String, Integer>();
        for (var t : Tier.values()) {
            rates.put(t.code(), t.takeRateBps());
        }
        return new EarningsResponses.Overview(
                o.headlineCents(),
                o.availableCents(),
                o.escrowNetCents(),
                o.escrowCount(),
                o.onHoldCents(),
                o.onHoldDisputes(),
                o.onHoldRefunds(),
                o.nextPayoutAt(),
                o.frequency(),
                o.tier(),
                o.takeRateBps(),
                Map.copyOf(rates));
    }

    default PayoutResponses.Overview toResponse(ViewPayouts.Overview o) {
        return new PayoutResponses.Overview(
                o.availableCents(),
                o.payableCents(),
                o.reserveCents(),
                o.nextPayoutAt(),
                toResponse(o.schedule()),
                o.account() == null ? null : toResponse(o.account()),
                o.pendingAccount() == null ? null : toResponse(o.pendingAccount()),
                o.pausedUntil(),
                new PayoutResponses.InstantTerms(
                        o.instantEligible(),
                        Fees.INSTANT_FEE_BPS,
                        Fees.INSTANT_MIN_FEE_CENTS,
                        Fees.INSTANT_MIN_AMOUNT_CENTS));
    }

    default CaseResponses.Overview toResponse(RespondToCases.Overview o) {
        var open = new ArrayList<CaseResponses.CaseDetail>();
        o.open().forEach(d -> open.add(toDetail(d)));
        o.awaitingRefunds().forEach(r -> open.add(toDetail(r)));
        var history = new ArrayList<CaseResponses.CaseRow>();
        o.refunds().stream().filter(r -> r.getDisputeId() == null).forEach(r -> history.add(toRow(r)));
        o.disputes().forEach(d -> history.add(toRow(d)));
        history.sort(Comparator.comparing(CaseResponses.CaseRow::openedAt).reversed());
        var rate = o.disputeRate();
        return new CaseResponses.Overview(
                o.open().size(),
                o.refundsLast30Days(),
                rate.jobs() == 0 ? 0 : (int) Math.round(rate.counted() * 10_000.0 / rate.jobs()),
                o.tier().disputeRateFloorBps(),
                open,
                history);
    }

    default CaseResponses.CaseDetail toDetail(Dispute d) {
        var offer = d.getOfferCents() == null || d.getOfferState() == null
                ? null
                : new CaseResponses.Offer(d.getOfferCents(), d.getOfferState().code(), d.getOfferExpiresAt());
        return new CaseResponses.CaseDetail(
                d.getId(),
                "dispute",
                d.getCaseNumber(),
                d.getSubject(),
                d.getAmountCents(),
                d.getCustomerName(),
                d.getCustomerStatement(),
                d.getResponse(),
                d.getResponseUpdatedAt(),
                toEvidence(d.getEvidence()),
                d.getState().code(),
                offer,
                d.getRespondBy(),
                false,
                d.getOpenedAt());
    }

    default CaseResponses.CaseDetail toDetail(Refund r) {
        return new CaseResponses.CaseDetail(
                r.getId(),
                "refund",
                r.getCaseNumber(),
                r.getWhat(),
                r.getAmountCents(),
                r.getCustomerName(),
                null,
                r.getContestReason(),
                null,
                List.of(),
                r.getState().code(),
                null,
                r.getContestBy(),
                r.isAuto(),
                r.getCreatedAt());
    }

    default CaseResponses.CaseRow toRow(Refund r) {
        return new CaseResponses.CaseRow(
                r.getId(),
                "refund",
                r.getCaseNumber(),
                r.getWhat(),
                r.getAmountCents(),
                r.getKind().code(),
                refundOutcome(r),
                r.getCreatedAt());
    }

    default CaseResponses.CaseRow toRow(Dispute d) {
        return new CaseResponses.CaseRow(
                d.getId(),
                "dispute",
                d.getCaseNumber(),
                d.getSubject(),
                d.getRefundCents() != null && d.getRefundCents() > 0 ? d.getRefundCents() : d.getAmountCents(),
                "refund",
                disputeOutcome(d),
                d.getOpenedAt());
    }

    private static String refundOutcome(Refund r) {
        return switch (r.getState()) {
            case PAID -> r.getKind() == Refund.Kind.CREDIT ? "goodwill" : r.isAuto() ? "auto_refunded" : "refunded";
            case APPROVED -> "queued";
            case SELLER_REVIEW, REQUESTED -> "awaiting_you";
            case AGENT_REVIEW -> "with_agent";
            case DENIED -> "denied";
        };
    }

    private static String disputeOutcome(Dispute d) {
        Dispute.@Nullable Decision decision = d.getDecision();
        return switch (d.getState()) {
            case DECIDED ->
                switch (decision == null ? Dispute.Decision.RELEASE : decision) {
                    case RELEASE -> "won";
                    case FULL_REFUND -> "lost";
                    case PARTIAL -> "partial";
                    case GOODWILL -> "goodwill";
                };
            case OPEN -> "awaiting_you";
            case SELLER_REPLIED -> "offer_sent";
            case AGENT, APPEALED -> "with_agent";
        };
    }
}
