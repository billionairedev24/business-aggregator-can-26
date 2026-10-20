package ca.northline.orders.domain;

import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A customer's return of delivered goods (S-151), one shop's lines: requested → approved (picked up on a run, or
 * dropped off at the shop) → collected (pickup) → received at the shop → refunded; or declined (→ escalated to a
 * Northline agent through the disputes queue), or withdrawn by the customer before the shop has it. Each transition
 * returns the next state; a transition that doesn't apply is a 409 {@code return_state}.
 *
 * @param state requested, approved, declined, escalated, collected (the courier has it), received (back at the shop),
 *     refunded or withdrawn
 * @param method {@code pickup} | {@code dropoff}, once approved
 * @param amountCents the lines' price before tax — the refund's amount (its tax goes back with it)
 */
public record OrderReturn(
        String id,
        String ref,
        String orderId,
        String merchantId,
        String customerId,
        String reason,
        @Nullable String note,
        @Nullable String photoKey,
        @Nullable String photoType,
        long amountCents,
        String state,
        @Nullable String method,
        boolean restock,
        @Nullable String declineReason,
        @Nullable String decidedBy,
        @Nullable Instant decidedAt,
        @Nullable Instant collectedAt,
        @Nullable Instant receivedAt,
        @Nullable Instant refundedAt,
        List<String> disputeIds,
        List<String> lineIds,
        Instant createdAt) {

    public OrderReturn {
        disputeIds = List.copyOf(disputeIds);
        lineIds = List.copyOf(lineIds);
    }

    /** The shop approves: how it comes back, and whether its units go back on sale once received. */
    public OrderReturn approve(String method, boolean restock, String by, Instant at) {
        if (!AfterSale.RETURN_METHODS.contains(method)) {
            throw RuleViolation.of("method", "required", AfterSale.RETURN_METHOD);
        }
        require("requested");
        return with("approved", method, restock, null, by, at, collectedAt, receivedAt, refundedAt, disputeIds);
    }

    public OrderReturn decline(String why, String by, Instant at) {
        if (why.isBlank()) {
            throw RuleViolation.of("reason", "required", AfterSale.DECLINE_REASON);
        }
        if (why.strip().length() > AfterSale.MAX_NOTE) {
            throw RuleViolation.of("reason", "length", AfterSale.DECLINE_LENGTH);
        }
        require("requested");
        return with("declined", method, false, why.strip(), by, at, collectedAt, receivedAt, refundedAt, disputeIds);
    }

    /** The courier has it (pickup). */
    public OrderReturn collected(Instant at) {
        require("approved");
        return with(
                "collected",
                method,
                restock,
                declineReason,
                decidedBy,
                decidedAt,
                at,
                receivedAt,
                refundedAt,
                disputeIds);
    }

    /** Back at the shop: by the courier, or dropped off (the shop marks it). */
    public OrderReturn received(Instant at) {
        if (!state.equals("approved") && !state.equals("collected")) {
            throw new Conflict("return_state", AfterSale.RETURN_STATE);
        }
        return with(
                "received",
                method,
                restock,
                declineReason,
                decidedBy,
                decidedAt,
                collectedAt,
                at,
                refundedAt,
                disputeIds);
    }

    public OrderReturn refunded(Instant at) {
        require("received");
        return with(
                "refunded",
                method,
                restock,
                declineReason,
                decidedBy,
                decidedAt,
                collectedAt,
                receivedAt,
                at,
                disputeIds);
    }

    /** The customer withdraws it while the shop doesn't have it yet. */
    public OrderReturn withdrawn(Instant at) {
        if (!state.equals("requested") && !state.equals("approved")) {
            throw new Conflict("return_state", AfterSale.RETURN_STATE);
        }
        return with(
                "withdrawn",
                method,
                restock,
                declineReason,
                decidedBy,
                decidedAt,
                collectedAt,
                receivedAt,
                refundedAt,
                disputeIds);
    }

    /** A declined return goes to a Northline agent (the disputes queue). */
    public OrderReturn escalated(List<String> disputes) {
        if (!state.equals("declined")) {
            throw new Conflict("not_declined", AfterSale.NOT_DECLINED);
        }
        return with(
                "escalated",
                method,
                restock,
                declineReason,
                decidedBy,
                decidedAt,
                collectedAt,
                receivedAt,
                refundedAt,
                disputes);
    }

    /** The return is still under way: its lines can't be in another one, and its money waits. */
    public boolean open() {
        return List.of("requested", "approved", "collected", "received").contains(state);
    }

    private void require(String expected) {
        if (!state.equals(expected)) {
            throw new Conflict("return_state", AfterSale.RETURN_STATE);
        }
    }

    private OrderReturn with(
            String next,
            @Nullable String nextMethod,
            boolean nextRestock,
            @Nullable String nextDecline,
            @Nullable String by,
            @Nullable Instant decided,
            @Nullable Instant collected,
            @Nullable Instant received,
            @Nullable Instant refunded,
            List<String> disputes) {
        return new OrderReturn(
                id,
                ref,
                orderId,
                merchantId,
                customerId,
                reason,
                note,
                photoKey,
                photoType,
                amountCents,
                next,
                nextMethod,
                nextRestock,
                nextDecline,
                by,
                decided,
                collected,
                received,
                refunded,
                disputes,
                lineIds,
                createdAt);
    }
}
