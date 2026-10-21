package ca.northline.orders.domain;

import ca.northline.shared.Conflict;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

/**
 * Group food orders (S-159): the share code, who may change what in which state, how a split bill is shared, and the
 * messages (English on the server; en + fr-CA in the apps, fr-CA in validation-messages.fr-CA.tsv).
 *
 * <p>A split bill: each person pays their dishes and the tax on them (Stripe Tax, per share); the service fee follows
 * the dishes (it is a percentage of them), the delivery fee is shared equally, the fees' tax follows the fees, and the
 * tip is the host's. Cents that don't divide go to the host's share, so the shares always add up to the order.
 */
public final class GroupOrderRules {
    private GroupOrderRules() {}

    public static final String PAYMENT = "Choose who pays: you, or each person their own share.";
    public static final String EMPTY = "Add something to the order before you lock it.";
    public static final String HOST_ITEMS = "To split the bill, add something for yourself first.";
    public static final String TOO_MANY = "You can add up to 30 dishes to a group order.";
    public static final String CODE_FORMAT = "Enter the code you were sent, like 7Q2M-K4XP.";

    public static final int MAX_ITEMS_PER_PERSON = 30;
    /** Open groups nobody locked or paid for are closed after this long (a meal is planned within the day). */
    public static final java.time.Duration LIFETIME = java.time.Duration.ofHours(24);

    public static final Set<String> PAYMENTS = Set.of("host", "split");
    /** States in which the group still waits for its host or its guests. */
    public static final Set<String> ACTIVE = Set.of("open", "locked", "paying");

    /** Without 0/O, 1/I/L, so a code read aloud or typed from a photo can't be mistaken. */
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    private static final RandomGenerator RANDOM = new SecureRandom();

    /** {@code 7Q2M-K4XP}: 8 characters of a 31-letter alphabet (≈ 40 bits) — the share link's last segment. */
    public static String newCode() {
        var chars = IntStream.range(0, 8)
                .mapToObj(_ -> String.valueOf(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()))))
                .toList();
        return String.join("", chars.subList(0, 4)) + "-" + String.join("", chars.subList(4, 8));
    }

    /** What a person typed ("7q2m k4xp", "7Q2MK4XP") as the stored code, or the 422 when it can't be one. */
    public static String normalizeCode(String typed) {
        var plain = typed.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-Z]", "");
        if (plain.length() != 8 || !plain.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0)) {
            throw ca.northline.shared.RuleViolation.of("code", "format", CODE_FORMAT);
        }
        return plain.substring(0, 4) + "-" + plain.substring(4);
    }

    // ── state rules (409s with their codes) ──────────────────────────────────────────────────────────────────

    /** Guests change the cart only while it is open. */
    public static void requireOpen(String state) {
        switch (state) {
            case "open" -> {}
            case "locked", "paying" ->
                throw new Conflict(
                        "group_locked", "The host has locked this order. Ask them to unlock it to make changes.");
            case "placed" -> throw new Conflict("group_placed", "This group order is with the kitchen now.");
            default -> throw closed();
        }
    }

    public static Conflict closed() {
        return new Conflict("group_closed", "This group order was cancelled or has expired.");
    }

    public static Conflict full(int max) {
        return new Conflict("group_full", "This group order is full (up to %d people).".formatted(max));
    }

    public static Conflict off(String kitchen) {
        return new Conflict("group_orders_off", kitchen + " doesn't take group orders.");
    }

    public static Conflict notLocked() {
        return new Conflict("group_not_locked", "Lock the order first, so nobody changes it while it's paid.");
    }

    public static Conflict unpaid() {
        return new Conflict("shares_unpaid", "Everyone has to pay their share before the order goes to the kitchen.");
    }

    public static Conflict sharesPaid() {
        return new Conflict(
                "shares_paid", "Someone has already paid, so the order can't be unlocked. Cancel it to start again.");
    }

    public static Conflict notCancellable() {
        return new Conflict(
                "not_cancellable",
                "The kitchen has this order now. Food orders can't be cancelled once they're with the kitchen.");
    }

    public static Conflict hostCantLeave() {
        return new Conflict("host_cannot_leave", "You're the host. Cancel the group order instead of leaving it.");
    }

    public static Conflict sharePaid() {
        return new Conflict("share_paid", "This share is already paid.");
    }

    public static Conflict wrongPayment(String payment) {
        return payment.equals("split")
                ? new Conflict("split_payment", "Each person pays their own share of this group order.")
                : new Conflict("host_payment", "The host pays for this group order.");
    }

    public static Conflict pricesChanged() {
        return new Conflict(
                "prices_changed",
                "A dish's price changed after the shares were paid. Cancel this group order and start a new one.");
    }

    // ── sharing the bill ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code total} split in proportion to {@code weights} (largest remainder; the first entry — the host — takes what
     * is left). All weights 0 → equal parts. The parts always add up to {@code total}.
     */
    public static List<Long> split(long total, List<Long> weights) {
        var n = weights.size();
        if (n == 0) {
            return List.of();
        }
        var sum = weights.stream().mapToLong(Long::longValue).sum();
        var w = sum == 0 ? weights.stream().map(_ -> 1L).toList() : weights;
        var weight = sum == 0 ? n : sum;
        var parts = new ArrayList<Long>(n);
        long given = 0;
        for (var x : w) {
            var part = Math.floorDiv(total * x, weight);
            parts.add(part);
            given += part;
        }
        parts.set(0, parts.getFirst() + (total - given));
        return List.copyOf(parts);
    }
}
