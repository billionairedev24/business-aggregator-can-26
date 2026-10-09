package ca.northline.orders.application;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Food checkout and tracking for the signed-in customer (S-57): price an order, open its card payment (the escrow
 * hold, released on handoff), place it once the card is authorized, and follow it through the kitchen display's
 * events. Use cases, their commands and read models.
 */
public final class FoodCheckout {
    private FoodCheckout() {}

    public interface QuoteFoodOrder {
        Totals quote(String customerId, Order order);
    }

    public interface StartFoodOrder {
        /**
         * @param mfa the sign-in used a second factor ({@code acr=mfa}); otherwise {@code stepUpProof} must be a fresh
         *     step-up (S-51's payment rule) — 403 {@code step_up_required} / {@code second_factor_required}
         * @param clientKey the request's Idempotency-Key (the Stripe call reuses it)
         */
        Started start(
                String customerId,
                boolean mfa,
                @Nullable String stepUpProof,
                Order order,
                String clientKey,
                Locale locale);
    }

    public interface PlaceFoodOrder {
        Placed place(String customerId, String orderId);
    }

    public interface TrackFoodOrder {
        Tracking track(String customerId, String orderId);
    }

    /**
     * What the customer asks for.
     *
     * @param mode {@code delivery} | {@code pickup}
     * @param scheduledFor the start of a scheduled window (one of the restaurant's {@code slots}); null = as soon as possible
     * @param delivery required for delivery: the saved address (Location screen) and drop-off preferences
     * @param promoCode mobile gaps part 2: a promo code on the dishes, null for none
     * @param usePoints spend points (up to the configured share of the dishes)
     */
    public record Order(
            String merchantId,
            String mode,
            @Nullable Instant scheduledFor,
            List<ItemLine> items,
            List<ComboLine> combos,
            Tip tip,
            @Nullable Delivery delivery,
            @Nullable String promoCode,
            boolean usePoints) {
        public Order {
            items = List.copyOf(items);
            combos = List.copyOf(combos);
        }

        public Order(
                String merchantId,
                String mode,
                @Nullable Instant scheduledFor,
                List<ItemLine> items,
                List<ComboLine> combos,
                Tip tip,
                @Nullable Delivery delivery) {
            this(merchantId, mode, scheduledFor, items, combos, tip, delivery, null, false);
        }
    }

    public record ItemLine(
            String itemId,
            int qty,
            List<String> optionIds,
            @Nullable String note) {
        public ItemLine {
            optionIds = List.copyOf(optionIds);
        }
    }

    public record ComboLine(String comboId, int qty, List<String> itemIds) {
        public ComboLine {
            itemIds = List.copyOf(itemIds);
        }
    }

    /** {@code none} · {@code amount} ({@code value} cents) · {@code percent} ({@code value} % of the dishes). */
    public record Tip(String kind, long value) {
        public static final Tip NONE = new Tip("none", 0);
    }

    /**
     * @param dropoff {@code hand} (Hand it to me) · {@code door} (Leave at door) · {@code lobby} (Meet in lobby)
     * @param extras {@code utensils}, {@code contactless}, {@code ring}
     */
    public record Delivery(
            String street,
            @Nullable String unit,
            @Nullable String city,
            String province,
            @Nullable String postalCode,
            double lat,
            double lng,
            @Nullable String zoneId,
            @Nullable String zone,
            String dropoff,
            @Nullable String note,
            List<String> extras) {
        public Delivery {
            extras = List.copyOf(extras);
        }
    }

    /** One priced line as the customer sees it. */
    public record Line(
            @Nullable String itemId,
            @Nullable String comboId,
            String title,
            int qty,
            long unitCents,
            long totalCents,
            List<String> choices,
            @Nullable String note,
            @Nullable String ageClass) {
        public Line {
            choices = List.copyOf(choices);
        }
    }

    /**
     * The order's money and times. {@code taxCents} = GST/HST/PST on the dishes (Stripe Tax at checkout; an estimate from
     * the province's rates in a quote) + on the fees ({@code feeTaxCents} of it).
     *
     * @param etaFromMin minutes until it arrives / is ready (as soon as possible), null when scheduled
     * @param discountCents the promo code's discount on the dishes ({@code taxCents} is on what's left)
     * @param pointsCents what points pay ({@code points} of them); {@code pointsAvailable} for the switch
     */
    public record Totals(
            String kitchen,
            String mode,
            @Nullable Instant scheduledFor,
            List<Line> lines,
            long subtotalCents,
            long deliveryFeeCents,
            long serviceFeeCents,
            long tipCents,
            long taxCents,
            long feeTaxCents,
            long totalCents,
            @Nullable Integer etaFromMin,
            @Nullable Integer etaToMin,
            boolean estimate,
            CheckoutUseCases.CheckoutAge age,
            @Nullable String promoCode,
            long discountCents,
            long points,
            long pointsCents,
            long pointsAvailable) {
        public Totals {
            lines = List.copyOf(lines);
        }
    }

    /**
     * The card payment to confirm with Stripe.js ({@code clientSecret}); {@code mode = fake} without Stripe keys (local),
     * where the checkout confirms with the simulated bank screen.
     */
    public record Started(
            String orderId,
            String ref,
            Totals totals,
            String paymentIntent,
            @Nullable String clientSecret,
            String status,
            String mode,
            @Nullable String publishableKey) {}

    public record Placed(String orderId, String ref) {}

    /**
     * Tracking (design 06 {@code foodTrack}), following the kitchen display: {@code stage} = paid (kitchen not yet
     * started) · cooking · ready · on_the_way (courier has it) · delivered (or picked up at the counter).
     */
    public record Tracking(
            String orderId,
            String ref,
            String kitchen,
            @Nullable String kitchenSlug,
            String mode,
            String state,
            String stage,
            Instant placedAt,
            @Nullable Instant scheduledFor,
            @Nullable Instant acceptedAt,
            @Nullable Integer prepMin,
            @Nullable Instant readyBy,
            @Nullable Instant readyAt,
            @Nullable Instant handedOffAt,
            @Nullable Instant deliveredAt,
            @Nullable Instant eta,
            long totalCents,
            List<Line> lines,
            @Nullable CourierProgress courier) {
        public Tracking {
            lines = List.copyOf(lines);
        }
    }
}
