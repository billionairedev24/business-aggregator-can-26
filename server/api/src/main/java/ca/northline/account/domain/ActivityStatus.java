package ca.northline.account.domain;

import ca.northline.shared.CodedEnum;

/**
 * The status tag of a row (design 06 {@code orderList}: "Packing", "Escrow", "Quote ready", "Done", "Case RF-2201").
 * The web turns the code into words in the reader's language.
 */
public enum ActivityStatus implements CodedEnum {
    PACKING,
    READY,
    ON_THE_WAY,
    DELIVERED,
    PAID,
    COOKING,
    REQUESTED,
    BOOKED,
    ESCROW,
    DEPOSIT_HELD,
    ON_SITE,
    COMPLETED,
    DONE,
    WAITING,
    QUOTE_READY,
    DECLINED,
    EXPIRED,
    REFUNDED,
    CANCELLED,
    /** A refund case or dispute is open on it ("Case RF-2201"). */
    CASE;

    /** Shop order states ({@code orders.orders.state}). */
    public static ActivityStatus ofOrder(String state) {
        return switch (state) {
            case "ready" -> READY;
            case "picked_up" -> ON_THE_WAY;
            case "delivered" -> DELIVERED;
            case "confirmed" -> DONE;
            case "refunded" -> REFUNDED;
            case "cancelled" -> CANCELLED;
            default -> PACKING;
        };
    }

    /** Food order states: paid → cooking (kitchen accepted) → ready → on the way → done. */
    public static ActivityStatus ofFood(String state) {
        return switch (state) {
            case "accepted", "packing" -> COOKING;
            case "ready" -> READY;
            case "picked_up" -> ON_THE_WAY;
            case "delivered", "confirmed" -> DONE;
            case "refunded" -> REFUNDED;
            case "cancelled" -> CANCELLED;
            default -> PAID;
        };
    }

    /**
     * Booking states. A confirmed, paid job shows where its money is: in escrow, or only the quote's deposit
     * ("Deposit held"); a free consultation is just booked.
     */
    public static ActivityStatus ofBooking(String state, boolean paid, boolean depositOnly) {
        return switch (state) {
            case "requested" -> REQUESTED;
            case "en_route" -> ON_THE_WAY;
            case "on_site" -> ON_SITE;
            case "completed" -> COMPLETED;
            case "signed_off" -> DONE;
            case "disputed" -> CASE;
            case "cancelled" -> CANCELLED;
            default -> !paid ? BOOKED : depositOnly ? DEPOSIT_HELD : ESCROW;
        };
    }

    /** "accent" (moving), "accent-2" (needs you) or "neutral" — the design's tag classes. */
    public String tone() {
        return switch (this) {
            case PACKING, READY, ON_THE_WAY, PAID, COOKING, ON_SITE -> "accent";
            case QUOTE_READY, CASE, COMPLETED -> "accent-2";
            default -> "neutral";
        };
    }
}
