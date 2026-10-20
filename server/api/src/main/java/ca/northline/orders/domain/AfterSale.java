package ca.northline.orders.domain;

import ca.northline.shared.CodedEnum;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * S-151: the rules after checkout — who may cancel a goods order and until when, a failed delivery's choice, and the
 * return window. The cut-off points are configuration (orders' {@code after-sale.*}); the messages are English here
 * and fr-CA in the message catalogue.
 */
public final class AfterSale {
    private AfterSale() {}

    // ── cancellation ──────────────────────────────────────────────────────────────────────────────────────────────

    /** Until when an actor may cancel (configuration: {@code customer-cancel-until} …). */
    public enum CancelUntil implements CodedEnum {
        /** While no shop has packed any of it (the customer's default). */
        PACKED,
        /** While the courier hasn't picked it up — for a shop, its own part of the order (the shop's default). */
        PICKED_UP,
        /** While it hasn't been delivered (Northline staff's default); a failed delivery can still be cancelled. */
        DELIVERED;

        /**
         * @param state the order's state
         * @param packed a shop has packed lines of it (for a shop: its own lines)
         * @param pickedUp the courier has picked it up (for a shop: from that shop)
         */
        public boolean allows(String state, boolean packed, boolean pickedUp) {
            return switch (this) {
                case PACKED -> Set.of("placed", "accepted", "packing").contains(state) && !packed && !pickedUp;
                case PICKED_UP ->
                    Set.of("placed", "accepted", "packing", "ready").contains(state) && !pickedUp;
                case DELIVERED ->
                    Set.of("placed", "accepted", "packing", "ready", "picked_up", "delivery_failed")
                            .contains(state);
            };
        }

        public String refusal() {
            return switch (this) {
                case PACKED -> TOO_LATE_PACKED;
                case PICKED_UP -> TOO_LATE_PICKED_UP;
                case DELIVERED -> TOO_LATE_DELIVERED;
            };
        }
    }

    public static final Set<String> CUSTOMER_REASONS =
            Set.of("changed_mind", "ordered_by_mistake", "delivery_too_late", "found_elsewhere", "other");
    public static final Set<String> MERCHANT_REASONS =
            Set.of("out_of_stock", "cannot_fulfil", "customer_request", "suspected_fraud", "other");
    public static final Set<String> STAFF_REASONS =
            Set.of("customer_request", "merchant_request", "fraud", "payment_issue", "delivery_issue", "other");
    /** The customer asked for a refund after a failed delivery, or the decision window passed. */
    public static final String DELIVERY_FAILED = "delivery_failed";

    public static final Set<String> CLOSED = Set.of("cancelled", "refunded", "returned");

    public static final String REASON = "Choose a reason.";
    public static final String NOTE_LENGTH = "Keep the note under 500 characters.";
    public static final String NOTE_REQUIRED = "Add a note for the reason “other”.";
    public static final String TOO_LATE_PACKED =
            "A shop has started packing this order, so it can't be cancelled now. You can return it after delivery.";
    public static final String TOO_LATE_PICKED_UP =
            "The courier has picked this order up, so it can't be cancelled now.";
    public static final String TOO_LATE_DELIVERED = "This order was delivered: start a return instead.";
    public static final String ALREADY_CLOSED = "This order is already cancelled or refunded.";
    public static final String NOT_GOODS = "Only shop orders can be cancelled here.";
    public static final String NOTHING_TO_CANCEL = "This shop has nothing left on the order to cancel.";

    public static final int MAX_NOTE = 500;

    // ── failed delivery ───────────────────────────────────────────────────────────────────────────────────────────

    public static final Set<String> CHOICES = Set.of("redeliver", "refund");
    public static final String CHOICE = "Choose re-delivery or a refund.";
    public static final String NOT_FAILED = "This order's delivery hasn't failed.";
    public static final String ALREADY_CHOSEN = "You've already chosen what happens to this order.";
    public static final String NO_RUN =
            "There's no delivery run to send it on right now. Choose a refund, or try later.";

    /** When the customer's choice is due after a failed drop-off; past it the order is refunded. */
    public static Instant decideBy(Instant failedAt, Duration within) {
        return failedAt.plus(within);
    }

    // ── returns ───────────────────────────────────────────────────────────────────────────────────────────────────

    public static final Set<String> RETURN_REASONS =
            Set.of("damaged", "wrong_item", "not_as_described", "missing_parts", "no_longer_needed", "other");
    public static final Set<String> RETURN_METHODS = Set.of("pickup", "dropoff");

    public static final String RETURN_REASON = "Choose why you're returning it.";
    public static final String RETURN_LINES = "Choose what you're returning.";
    public static final String RETURN_PHOTO = "Add a photo of the item.";
    public static final String RETURN_PHOTO_FILE = "Upload a JPG, PNG or WebP image under 5 MB.";
    public static final String RETURN_NOTE_LENGTH = "Keep the note under 1,000 characters.";
    public static final String NOT_DELIVERED = "You can return items once the order is delivered.";
    public static final String WINDOW_CLOSED = "The return window for this item has closed.";
    public static final String NOT_RETURNABLE = "This item can't be returned.";
    public static final String ALREADY_RETURNING = "This item is already being returned.";
    public static final String ONE_SHOP = "Return items from one shop at a time.";
    public static final String RETURN_METHOD = "Choose pickup or drop-off.";
    public static final String DECLINE_REASON = "Say why the return is declined.";
    public static final String DECLINE_LENGTH = "Keep the reason under 500 characters.";
    public static final String RETURN_STATE = "This return can't change that way now.";
    public static final String NOT_DECLINED = "Only a declined return can go to a Northline agent.";
    public static final String ESCALATE_STATEMENT = "Tell the agent why the return should be accepted.";

    public static final int MAX_RETURN_NOTE = 1000;
    public static final long MAX_PHOTO_BYTES = 5L * 1024 * 1024;

    /** A JPG, PNG or WebP from the file's first bytes (never the name or the type the client sent), else null. */
    public static @Nullable String imageType(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length > 12
                && b[0] == 'R'
                && b[1] == 'I'
                && b[2] == 'F'
                && b[3] == 'F'
                && b[8] == 'W'
                && b[9] == 'E'
                && b[10] == 'B'
                && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /** Whether a line delivered at {@code deliveredAt} can still be returned at {@code now} ({@code days} 0 = never). */
    public static boolean returnable(@Nullable Instant deliveredAt, int days, Instant now) {
        return deliveredAt != null && days > 0 && now.isBefore(deliveredAt.plus(Duration.ofDays(days)));
    }
}
