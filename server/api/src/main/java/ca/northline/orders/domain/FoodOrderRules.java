package ca.northline.orders.domain;

import ca.northline.shared.RuleViolation;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Food checkout rules the spec leaves to us (S-57): tips (design: No tip · $2 · $4 · 15 % · $6; up to $100 or 30 %),
 * the tracking stage a customer sees, and the messages (English on the server; en + fr-CA in the web app).
 */
public final class FoodOrderRules {
    private FoodOrderRules() {}

    public static final String MODE = "Choose delivery or pickup.";
    public static final String ADDRESS_REQUIRED = "Add your delivery address first.";
    public static final String SLOT = "Choose one of the windows offered.";
    public static final String TIP = "Choose a tip between $0 and $100, or up to 30 %.";
    public static final String PROVINCE_FORMAT = "Choose a Canadian province or territory.";
    public static final Pattern PROVINCE = Pattern.compile("^(AB|BC|MB|NB|NL|NS|NT|NU|ON|PE|QC|SK|YT)$");

    public static final String DROPOFF = "Choose how to hand it over.";
    public static final String EXTRAS = "Choose from the extras offered.";
    public static final String NOTE = "Keep the note under 140 characters.";
    public static final java.util.Set<String> DROPOFFS = java.util.Set.of("hand", "door", "lobby");
    public static final java.util.Set<String> EXTRA_CODES = java.util.Set.of("utensils", "contactless", "ring");

    public static final long MAX_TIP_CENTS = 10_000;
    public static final long MAX_TIP_PERCENT = 30;

    /** "Add $4.50 to reach the $15 minimum." */
    public static String belowMinimum(long missingCents) {
        return String.format(
                Locale.ROOT, "Add $%d.%02d to reach the $15 minimum.", missingCents / 100, missingCents % 100);
    }

    public static long tipCents(String kind, long value, long subtotalCents) {
        return switch (kind) {
            case "none" -> 0;
            case "amount" -> {
                if (value < 0 || value > MAX_TIP_CENTS) {
                    throw RuleViolation.of("tip.value", "range", TIP);
                }
                yield value;
            }
            case "percent" -> {
                if (value < 0 || value > MAX_TIP_PERCENT) {
                    throw RuleViolation.of("tip.value", "range", TIP);
                }
                yield Math.round(subtotalCents * value / 100.0);
            }
            default -> throw RuleViolation.of("tip.kind", "required", TIP);
        };
    }

    /**
     * paid (the kitchen hasn't accepted) · cooking · ready · on_the_way (courier picked it up) · delivered. Follows
     * {@code orders.orders.state}, which the kitchen display's events move; the ticket fills the gap before the
     * orders listener has run.
     */
    public static String stage(String orderState, @Nullable String ticketStage, String mode) {
        return switch (orderState) {
            case "delivered", "confirmed" -> "delivered";
            case "picked_up" -> "on_the_way";
            case "refunded", "cancelled" -> orderState;
            // the kitchen display's ticket moves first; the order's state follows when the orders listener has run
            default -> {
                if ("handed_off".equals(ticketStage)) {
                    yield mode.equals("pickup") ? "delivered" : "on_the_way";
                }
                if ("ready".equals(ticketStage) || orderState.equals("ready")) {
                    yield "ready";
                }
                yield "cooking".equals(ticketStage) || orderState.equals("accepted") || orderState.equals("packing")
                        ? "cooking"
                        : "paid";
            }
        };
    }

    /** Drop-off, extras and the courier note of a delivery. */
    public static void checkDelivery(
            String dropoff, java.util.List<String> extras, @Nullable String note, @Nullable String unit) {
        var out = new java.util.ArrayList<RuleViolation.Violation>();
        if (!DROPOFFS.contains(dropoff)) {
            out.add(new RuleViolation.Violation("delivery.dropoff", "required", DROPOFF));
        }
        if (!EXTRA_CODES.containsAll(extras)) {
            out.add(new RuleViolation.Violation("delivery.extras", "format", EXTRAS));
        }
        if (note != null && note.length() > 140) {
            out.add(new RuleViolation.Violation("delivery.note", "length", NOTE));
        }
        if (unit != null && unit.length() > 120) {
            out.add(new RuleViolation.Violation("delivery.unit", "length", "At most 120 characters."));
        }
        if (!out.isEmpty()) {
            throw new RuleViolation(out);
        }
    }
}
