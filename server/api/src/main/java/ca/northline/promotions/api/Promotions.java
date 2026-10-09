package ca.northline.promotions.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Promo codes and points at checkout (mobile gaps part 2), for the three checkouts: goods orders and food orders
 * (orders) and bookings (hire).
 *
 * <ul>
 *   <li>A code takes {@code discountCents} off the merchants' amounts before tax: GST/HST/PST/QST are computed on what is
 *       left ({@link Line#taxableCents()}). It is spread over the lines it applies to in proportion to their amounts;
 *       every line keeps at least $1.00 to pay by card.
 *   <li>Points pay part of what is left (at most the configured share of each line's taxable amount); they don't lower
 *       the tax — they pay like money, and Northline funds them.
 * </ul>
 *
 * {@link #price} only checks (quotes); {@link #reserve} holds the code's use and the points while the card is
 * authorized; {@link #redeem} takes the points when the order is placed; {@link #release} gives everything back when
 * the checkout is abandoned. 422 messages on {@code promoCode}.
 */
public interface Promotions {

    /** @param kind {@code goods} | {@code food} | {@code service} */
    record Basket(String customerId, String kind, List<Item> items) {
        public Basket {
            items = List.copyOf(items);
        }
    }

    /**
     * One escrow-to-be.
     *
     * @param escrowRefType {@code order_line} | {@code food_order} | {@code booking}
     * @param amountCents the merchant's amount before any discount and tax
     */
    record Item(String escrowRefType, String escrowRefId, String merchantId, long amountCents) {}

    /** @param code the customer's code (any case, spaces ignored), null or blank for none */
    record Ask(@Nullable String code, boolean usePoints) {
        public static final Ask NONE = new Ask(null, false);
    }

    /**
     * @param fundedBy "northline" or "merchant" — who carries this line's discount; null without one
     * @param pointsCents what points pay of this line (its amount after the discount, plus tax)
     */
    record Line(
            String escrowRefType,
            String escrowRefId,
            String merchantId,
            long amountCents,
            long discountCents,
            @Nullable String fundedBy,
            long pointsCents) {

        /** What tax is computed on and the escrow holds: the amount less the code's discount. */
        public long taxableCents() {
            return amountCents - discountCents;
        }
    }

    /**
     * @param code the code as stored (upper case), null without one
     * @param pointsAvailable the customer's points that could be spent now (for the "Use my points" switch)
     */
    record Priced(
            @Nullable String code,
            long discountCents,
            @Nullable String fundedBy,
            long points,
            long pointsCents,
            long pointsAvailable,
            List<Line> lines) {

        public Priced {
            lines = List.copyOf(lines);
        }

        public Optional<Line> line(String escrowRefType, String escrowRefId) {
            return lines.stream()
                    .filter(l -> l.escrowRefType().equals(escrowRefType)
                            && l.escrowRefId().equals(escrowRefId))
                    .findFirst();
        }

        public static Priced none(List<Item> items, long pointsAvailable) {
            return new Priced(
                    null,
                    0,
                    null,
                    0,
                    0,
                    pointsAvailable,
                    items.stream()
                            .map(i -> new Line(
                                    i.escrowRefType(), i.escrowRefId(), i.merchantId(), i.amountCents(), 0, null, 0))
                            .toList());
        }
    }

    /** Checks the code and works out the points; writes nothing. */
    Priced price(Basket basket, Ask ask);

    /**
     * Holds the code's use and the points for the checkout {@code refId} until {@code until} (replacing what an
     * earlier attempt of the same checkout held); nothing is held when nothing applies.
     */
    Priced reserve(String refId, Instant until, Basket basket, Ask ask);

    /** What the checkout holds (none when it held nothing). */
    Priced reserved(String kind, String refId);

    /** The order is placed / the booking confirmed: the points leave the wallet, the code counts as used. */
    void redeem(String kind, String refId);

    /** The checkout was abandoned or expired: the code's use and any points taken go back. */
    void release(String kind, String refId);
}
