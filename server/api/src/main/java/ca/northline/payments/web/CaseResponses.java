package ca.northline.payments.web;

import ca.northline.payments.domain.Evidence;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** JSON of the Refunds &amp; disputes screen. */
final class CaseResponses {
    private CaseResponses() {}

    /**
     * {@code GET /refunds}: headline counts, the open cases (detail) and the history (table rows).
     *
     * @param disputeRateBps disputes that count against the merchant ÷ jobs &amp; orders, trailing 12 months
     * @param disputeRateFloorBps the tier's floor ("floor for Master: 1%")
     */
    record Overview(
            int openDisputes,
            int refundsLast30Days,
            int disputeRateBps,
            int disputeRateFloorBps,
            List<CaseDetail> open,
            List<CaseRow> history) {}

    /** A piece of evidence; {@code downloadable} when a file is stored. */
    record EvidenceItem(
            String id,
            Evidence.Kind kind,
            String name,
            @Nullable String contentType,
            long size,
            String by,
            Instant at,
            boolean downloadable) {}

    record Offer(long amountCents, String state, @Nullable Instant expiresAt) {}

    /**
     * An open case. {@code type}: dispute | refund. {@code state}: the aggregate state ({@code open},
     * {@code seller_replied}, {@code agent} for disputes; {@code seller_review} … for refunds). {@code dueBy}: reply by
     * (dispute) or contest by (refund).
     */
    record CaseDetail(
            String id,
            String type,
            String caseNumber,
            String subject,
            long amountCents,
            @Nullable String customerName,
            @Nullable String customerStatement,
            @Nullable String response,
            @Nullable Instant responseUpdatedAt,
            List<EvidenceItem> evidence,
            String state,
            @Nullable Offer offer,
            @Nullable Instant dueBy,
            boolean auto,
            Instant openedAt) {}

    /**
     * A History row. {@code outcome}: auto_refunded | goodwill | refunded | queued | awaiting_you | with_agent | denied
     * (refunds); won | lost | partial | goodwill | offer_sent | awaiting_you | with_agent (disputes).
     */
    record CaseRow(
            String id,
            String type,
            String caseNumber,
            String what,
            long amountCents,
            String kind,
            String outcome,
            Instant openedAt) {}
}
