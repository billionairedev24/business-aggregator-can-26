package ca.northline.booking.domain;

import ca.northline.booking.api.QuoteAccepted;
import ca.northline.booking.api.QuoteSent;
import ca.northline.booking.domain.QuoteEnums.QuoteState;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * A merchant's itemized reply to a quote request (aggregate root). Honoured as written (Alberta CPA ±10 %), so it is
 * <b>immutable once sent</b>: a change is a {@link #revise revision} — a new row with version + 1 — and the prior
 * version becomes {@code superseded}. Totals are always derived from the content ({@link QuoteTotals}); the V016 trigger
 * re-checks subtotal = Σ lines at commit.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Quote {

    @EqualsAndHashCode.Include
    @ToString.Include
    private final String id;

    private final String requestId;
    private final String merchantId;

    @ToString.Include
    private final String ref;

    @ToString.Include
    private final int version;

    private QuoteContent content;
    private QuoteTotals totals;

    @ToString.Include
    private QuoteState state;

    private final String createdBy;
    private final Instant createdAt;
    private @Nullable Instant sentAt;
    private @Nullable Instant validUntil;
    private final @Nullable Instant viewedAt;

    /** A new draft (version 1, or the next version of a revision). */
    public static Quote draft(
            String requestId,
            String merchantId,
            String ref,
            int version,
            QuoteContent content,
            int taxBps,
            String actorId,
            Instant at) {
        content.validate();
        return new Quote(
                Ids.next(),
                requestId,
                merchantId,
                ref,
                version,
                content,
                QuoteTotals.of(content, taxBps),
                QuoteState.DRAFT,
                actorId,
                at,
                null,
                null,
                null);
    }

    /** Replaces the content of a draft (drafts are the only quotes that change). */
    public void redraft(QuoteContent newContent, int taxBps) {
        requireState(QuoteState.DRAFT, "edited");
        content = newContent.validate();
        totals = QuoteTotals.of(newContent, taxBps);
    }

    /**
     * Sends the draft to the customer: valid for {@code validHours} from now.
     *
     * @param supersededQuoteId the prior version when this is a revision
     */
    public QuoteSent send(String actorId, Instant at, @Nullable String supersededQuoteId) {
        requireState(QuoteState.DRAFT, "sent");
        state = QuoteState.SENT;
        sentAt = at;
        validUntil = at.plus(Duration.ofHours(content.validHours()));
        return new QuoteSent(
                Ids.next(),
                at,
                id,
                requestId,
                merchantId,
                actorId,
                version,
                totals.totalCents(),
                totals.depositCents(),
                validUntil,
                supersededQuoteId);
    }

    /**
     * "Revise": marks this sent quote superseded and returns the next version as a draft with the new content (same
     * reference). The caller sends it in the same transaction.
     */
    public Quote revise(QuoteContent newContent, int taxBps, String actorId, Instant at) {
        if (!state.isOpen()) {
            throw new Conflict("quote_state", "This quote is %s and can no longer be revised.".formatted(state.code()));
        }
        state = QuoteState.SUPERSEDED;
        return draft(requestId, merchantId, ref, version + 1, newContent, taxBps, actorId, at);
    }

    /**
     * The customer accepts (consumer app): the total moves to escrow and the time is booked.
     *
     * @param requestCustomerId the customer who asked for the quote
     */
    public QuoteAccepted accept(String customerId, String requestCustomerId, Instant at) {
        if (!customerId.equals(requestCustomerId)) {
            throw new Conflict("not_customer", "Only the customer can accept this quote.");
        }
        if (!state.isOpen()) {
            throw new Conflict("quote_state", "This quote can no longer be accepted.");
        }
        if (validUntil != null && !at.isBefore(validUntil)) {
            throw new Conflict("quote_expired", "This quote has expired. Ask for an updated quote.");
        }
        state = QuoteState.ACCEPTED;
        return new QuoteAccepted(
                Ids.next(), at, id, merchantId, customerId, totals.totalCents(), totals.depositCents());
    }

    /**
     * The customer declines (consumer app): the provider is told; the request's other quotes stay open.
     *
     * @param requestCustomerId the customer who asked for the quote
     */
    public void decline(String customerId, String requestCustomerId) {
        if (!customerId.equals(requestCustomerId)) {
            throw new Conflict("not_customer", "Only the customer can decline this quote.");
        }
        if (!state.isOpen()) {
            throw new Conflict("quote_state", "This quote can no longer be declined.");
        }
        state = QuoteState.DECLINED;
    }

    private void requireState(QuoteState expected, String action) {
        if (state != expected) {
            throw new Conflict("quote_state", "This quote is %s and can't be %s.".formatted(state.code(), action));
        }
    }
}
