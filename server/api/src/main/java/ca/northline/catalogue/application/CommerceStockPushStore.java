package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.CommerceProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-158): {@code catalogue.commerce_stock_pushes} — Northline's sales and restocks waiting for, sent to
 * or refused by a connected platform — and the lookups that turn an order line into a platform variant.
 */
public interface CommerceStockPushStore {

    enum State {
        PENDING,
        DONE,
        CONFLICT,
        FAILED,
        DISMISSED
    }

    /**
     * @param conflict why it needs the merchant ({@code oversold}, {@code scope_missing}, {@code choose_location},
     *     {@code not_found}, {@code reconnect}, {@code disconnected}, {@code rejected}, {@code ambiguous})
     * @param doneAt when the platform applied it (also set for {@code oversold}: applied, but the platform went below 0)
     */
    record Push(
            String id,
            String merchantId,
            CommerceProvider provider,
            String integrationId,
            String offerId,
            String sku,
            String externalVariantId,
            @Nullable String stockRef,
            int delta,
            String reason,
            String sourceRef,
            String idempotencyKey,
            State state,
            @Nullable String conflict,
            int attempts,
            Instant nextAttemptAt,
            @Nullable String lastError,
            @Nullable String locationId,
            @Nullable Integer platformAfter,
            Instant createdAt,
            @Nullable Instant doneAt) {}

    /** A connected platform variant a Northline SKU is linked to (one per integration and variant). */
    record Target(
            String integrationId,
            CommerceProvider provider,
            String externalVariantId,
            @Nullable String stockRef) {}

    /** Pushes waiting, done in the period, and needing the merchant. */
    record Counts(int pending, int done, int attention) {}

    /** The SKU of an offer (no variant) or of one of its variants; empty when it has none. */
    Optional<String> skuOf(String merchantId, String offerId, @Nullable String variantId);

    /** The connected integrations' variants linked to this SKU of the offer (removed products excluded). */
    List<Target> targets(String merchantId, String offerId, String sku);

    /** Inserts a push; false when one with the same idempotency key exists (an event delivered twice). */
    boolean insert(Push push);

    /**
     * Claims due pending pushes for this replica: their {@code next_attempt_at} moves to {@code leaseUntil} (skip
     * locked), so another replica doesn't send them while this one does.
     */
    List<Push> claimDue(Instant now, Instant leaseUntil, int limit);

    void complete(String id, @Nullable String locationId, @Nullable Integer after, Instant at);

    /** A refusal; {@code appliedAt} set when the platform applied it all the same ({@code oversold}). */
    void conflict(
            String id,
            String conflict,
            String detail,
            @Nullable String locationId,
            @Nullable Integer after,
            @Nullable Instant appliedAt,
            Instant at);

    void retryLater(String id, int attempts, Instant next, String error);

    void fail(String id, int attempts, String error, Instant at);

    Optional<Push> find(String merchantId, String id);

    /** Back to pending, attempts reset (the merchant's "Retry"). */
    void requeue(String id, String by, Instant at);

    void dismiss(String id, String by, Instant at);

    /** After a reconnect or a location choice: the integration's unapplied conflicts with these codes go again. */
    int requeueConflicts(String integrationId, List<String> conflicts, Instant at);

    /** Conflicts and failures of the connection, newest first. */
    List<Push> attention(String merchantId, CommerceProvider provider, int limit);

    Counts counts(String merchantId, CommerceProvider provider, Instant doneSince);

    /** The net change still waiting per SKU (sales negative), to keep a platform read from undoing a sale. */
    Map<String, Integer> pendingBySku(String merchantId, CommerceProvider provider);
}
