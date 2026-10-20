package ca.northline.trust.domain;

import ca.northline.shared.RuleViolation.Violation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-75: a business's provider-funded reward (design 02: "2× points on brake jobs · until Oct 1 — You pay the extra
 * points ($0.01 each); Northline routes them to your customers"). One per business, switched on and off. S-152 gives it
 * money: a budget the extra points may cost (drawn down as sales earn them; the reward stops when it runs out) and,
 * optionally, the listings it applies to.
 *
 * @param label what the extra points are on ("brake jobs"); null = everything the business sells
 * @param endsOn the last day it runs, in the business's time zone
 * @param budgetCents what the extra points may cost the business in all; null on rewards from before S-152 (they
 *     credit nothing until a budget is set)
 * @param spentCents what they have cost so far
 * @param listingIds the listings it applies to; empty = everything
 * @param startedAt when it was last switched on (sales from then on earn extra points)
 * @param exhaustedAt when the budget ran out; null while some is left
 */
public record MerchantReward(
        String merchantId,
        boolean active,
        int multiplier,
        @Nullable String label,
        LocalDate endsOn,
        Instant updatedAt,
        @Nullable Long budgetCents,
        long spentCents,
        List<String> listingIds,
        @Nullable Instant startedAt,
        @Nullable Instant exhaustedAt) {

    public static final int LABEL_MAX = 60;
    public static final int HORIZON_DAYS = 90;
    public static final long BUDGET_MIN_CENTS = 1_000;
    public static final long BUDGET_MAX_CENTS = 1_000_000;
    public static final int LISTINGS_MAX = 50;
    public static final String MULTIPLIER = "Choose 2× or 3× points.";
    public static final String ACTIVE_REQUIRED = "Say whether the reward is on.";
    public static final String ENDS_REQUIRED = "Choose when the reward ends.";
    public static final String ENDS_RANGE = "Pick an end date within the next 90 days.";
    public static final String LABEL_TOO_LONG = "At most 60 characters.";
    public static final String BUDGET_REQUIRED = "Set a budget for the extra points.";
    public static final String BUDGET_RANGE = "Choose a budget between $10 and $10,000.";
    public static final String BUDGET_BELOW_SPENT = "The budget can't be less than what the reward has already spent.";
    public static final String LISTINGS_TOO_MANY = "Choose at most 50 listings.";

    public MerchantReward {
        label = label == null || label.isBlank() ? null : label.strip();
        listingIds = List.copyOf(listingIds);
    }

    /** Is it running on {@code today} (the business's date) with budget left? */
    public boolean runningOn(LocalDate today) {
        return active && !endsOn.isBefore(today) && exhaustedAt == null && budgetCents != null;
    }

    /** Budget not yet spent. */
    public long leftCents() {
        return budgetCents == null ? 0 : Math.max(0, budgetCents - spentCents);
    }

    /** Whether a sale of {@code listingId} (null when unknown) is one the reward applies to. */
    public boolean covers(@Nullable String listingId) {
        return listingIds.isEmpty() || (listingId != null && listingIds.contains(listingId));
    }

    /** The rules a switched-on reward follows; switching off is always allowed. */
    public List<Violation> validate(LocalDate today) {
        var out = new ArrayList<Violation>();
        if (budgetCents != null && budgetCents < spentCents) {
            out.add(new Violation("budgetCents", "range", BUDGET_BELOW_SPENT));
        }
        if (!active) {
            return out;
        }
        if (multiplier != 2 && multiplier != 3) {
            out.add(new Violation("multiplier", "range", MULTIPLIER));
        }
        if (endsOn.isBefore(today) || endsOn.isAfter(today.plusDays(HORIZON_DAYS))) {
            out.add(new Violation("endsOn", "range", ENDS_RANGE));
        }
        if (label != null && label.length() > LABEL_MAX) {
            out.add(new Violation("label", "length", LABEL_TOO_LONG));
        }
        if (budgetCents == null) {
            out.add(new Violation("budgetCents", "required", BUDGET_REQUIRED));
        } else if ((budgetCents < BUDGET_MIN_CENTS || budgetCents > BUDGET_MAX_CENTS) && budgetCents >= spentCents) {
            out.add(new Violation("budgetCents", "range", BUDGET_RANGE));
        }
        if (listingIds.size() > LISTINGS_MAX) {
            out.add(new Violation("listingIds", "length", LISTINGS_TOO_MANY));
        }
        return out;
    }
}
