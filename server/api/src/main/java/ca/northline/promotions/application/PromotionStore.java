package ca.northline.promotions.application;

import ca.northline.promotions.api.Promotions.Line;
import ca.northline.promotions.domain.PromoCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: {@code promotions.codes}, {@code redemptions} and {@code redemption_lines}. */
public interface PromotionStore {

    Optional<PromoCode> byCode(String code);

    /** The code's row locked for the rest of the transaction (limits are counted under it). */
    Optional<PromoCode> lockByCode(String code);

    Optional<PromoCode> byId(String id);

    List<PromoCode> all(int limit);

    /** False when the code already exists. */
    boolean insert(PromoCode code, String staffId, Instant at);

    boolean setActive(String id, boolean active, Instant at);

    /** Uses that count: redeemed, or reserved and not lapsed — other checkouts than {@code exceptKind/exceptRef}. */
    int uses(String codeId, @Nullable String customerId, Instant now, String exceptKind, String exceptRef);

    /** Redeemed uses and the discount they gave (the console list). */
    record Usage(int redeemed, long discountCents) {}

    Usage usage(String codeId);

    /** Points other open checkouts of the customer hold (reserved, not lapsed). */
    long pointsHeld(String customerId, Instant now, String exceptKind, String exceptRef);

    /** Locks the customer's points for the rest of the transaction. */
    void lockCustomer(String customerId);

    /**
     * @param state {@code reserved} | {@code redeemed} | {@code released}
     */
    record Redemption(
            String id,
            String customerId,
            String kind,
            String refId,
            @Nullable String codeId,
            @Nullable String code,
            @Nullable String fundedBy,
            long discountCents,
            long points,
            long pointsCents,
            String state,
            Instant reservedUntil,
            List<Line> lines) {
        public Redemption {
            lines = List.copyOf(lines);
        }
    }

    Optional<Redemption> redemption(String kind, String refId);

    /** The redemption whose lines include this escrow. */
    Optional<Redemption> byEscrow(String escrowRefType, String escrowRefId);

    /** Replaces the checkout's redemption (an earlier attempt's is deleted with its lines). */
    void save(Redemption redemption, Instant at);

    void delete(String kind, String refId);

    boolean redeemed(String id, Instant at);

    boolean released(String id, Instant at);

    /** Adds to what refunds gave back of a line's points; returns the new total. */
    long pointsReturned(String escrowRefType, String escrowRefId, long cents);
}
