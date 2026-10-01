package ca.northline.trust.domain;

import ca.northline.shared.RuleViolation.Violation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-75: a business's provider-funded reward (design 02: "2× points on brake jobs · until Oct 1 — You pay the extra
 * points ($0.01 each); Northline routes them to your customers"). One per business, switched on and off.
 *
 * @param label what the extra points are on ("brake jobs"); null = everything the business sells
 * @param endsOn the last day it runs, in the business's time zone
 */
public record MerchantReward(
        String merchantId,
        boolean active,
        int multiplier,
        @Nullable String label,
        LocalDate endsOn,
        Instant updatedAt) {

    public static final int LABEL_MAX = 60;
    public static final int HORIZON_DAYS = 90;
    public static final String MULTIPLIER = "Choose 2× or 3× points.";
    public static final String ACTIVE_REQUIRED = "Say whether the reward is on.";
    public static final String ENDS_REQUIRED = "Choose when the reward ends.";
    public static final String ENDS_RANGE = "Pick an end date within the next 90 days.";
    public static final String LABEL_TOO_LONG = "At most 60 characters.";

    public MerchantReward {
        label = label == null || label.isBlank() ? null : label.strip();
    }

    /** Is it running on {@code today} (the business's date)? */
    public boolean runningOn(LocalDate today) {
        return active && !endsOn.isBefore(today);
    }

    /** The rules a switched-on reward follows; switching off is always allowed. */
    public List<Violation> validate(LocalDate today) {
        var out = new ArrayList<Violation>();
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
        return out;
    }
}
