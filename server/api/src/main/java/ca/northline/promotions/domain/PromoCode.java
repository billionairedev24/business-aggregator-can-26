package ca.northline.promotions.domain;

import ca.northline.shared.RuleViolation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A promo code (aggregate root, {@code promotions.codes}). {@link #discount} answers what it takes off the lines it
 * applies to; the limits are checked against the redemptions by the service, under a lock on the code.
 *
 * @param kind {@code percent} | {@code amount}
 * @param fundedBy "northline" (every merchant's lines) or "merchant" (only the funding business's lines)
 * @param appliesTo {@code goods} | {@code food} | {@code service}
 */
public record PromoCode(
        String id,
        String code,
        @Nullable String description,
        String kind,
        @Nullable Integer percent,
        @Nullable Long amountCents,
        @Nullable Long maxDiscountCents,
        long minSpendCents,
        Instant startsAt,
        Instant endsAt,
        int perCustomerLimit,
        @Nullable Integer totalLimit,
        String fundedBy,
        @Nullable String merchantId,
        Set<String> appliesTo,
        boolean active) {

    public static final Pattern FORMAT = Pattern.compile("[A-Z0-9][A-Z0-9-]{1,18}[A-Z0-9]");
    public static final Set<String> KINDS = Set.of("goods", "food", "service");
    public static final String NORTHLINE = "northline";
    public static final String MERCHANT = "merchant";

    public PromoCode {
        appliesTo = Set.copyOf(appliesTo);
    }

    /** Whether the code may be used at {@code now} for a {@code kind} checkout; the reason as a 422 otherwise. */
    public void checkUsable(String kind, Instant now) {
        if (!active) {
            throw violation(PromoMessages.UNKNOWN);
        }
        if (now.isBefore(startsAt)) {
            throw violation(PromoMessages.NOT_YET);
        }
        if (!now.isBefore(endsAt)) {
            throw violation(PromoMessages.EXPIRED);
        }
        if (!appliesTo.contains(kind)) {
            throw violation(PromoMessages.NOT_HERE);
        }
    }

    /** Whether a line of {@code merchant} counts towards this code. */
    public boolean covers(String merchant) {
        return NORTHLINE.equals(fundedBy) || merchant.equals(merchantId);
    }

    /**
     * The discount on {@code eligibleCents} (the lines it covers), before the per-line floor of $1.00.
     *
     * @throws RuleViolation below the minimum spend, or with nothing it covers
     */
    public long discount(long eligibleCents) {
        if (eligibleCents <= 0) {
            throw violation(PromoMessages.NOT_HERE);
        }
        if (eligibleCents < minSpendCents) {
            throw violation(PromoMessages.minSpend(minSpendCents));
        }
        if ("percent".equals(kind)) {
            var off = BigDecimal.valueOf(eligibleCents)
                    .multiply(BigDecimal.valueOf(java.util.Objects.requireNonNull(percent)))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                    .longValueExact();
            return maxDiscountCents == null ? off : Math.min(off, maxDiscountCents);
        }
        return java.util.Objects.requireNonNull(amountCents);
    }

    public static RuleViolation violation(String message) {
        return RuleViolation.of("promoCode", "promo", message);
    }

    /** The console's checks of a new code, one message per field. */
    public static List<RuleViolation.Violation> problems(
            String code,
            @Nullable String kind,
            @Nullable Integer percent,
            @Nullable Long amountCents,
            @Nullable Long maxDiscountCents,
            @Nullable Long minSpendCents,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable Integer perCustomerLimit,
            @Nullable Integer totalLimit,
            @Nullable String fundedBy,
            @Nullable String merchantId,
            List<String> appliesTo,
            @Nullable String description) {
        var out = new java.util.ArrayList<RuleViolation.Violation>();
        if (!FORMAT.matcher(code).matches()) {
            out.add(new RuleViolation.Violation("code", "format", PromoMessages.CODE_FORMAT));
        }
        if (!"percent".equals(kind) && !"amount".equals(kind)) {
            out.add(new RuleViolation.Violation("kind", "required", PromoMessages.KIND));
        } else if ("percent".equals(kind) && (percent == null || percent < 1 || percent > 100)) {
            out.add(new RuleViolation.Violation("percent", "range", PromoMessages.PERCENT));
        } else if ("amount".equals(kind) && (amountCents == null || amountCents < 100)) {
            out.add(new RuleViolation.Violation("amountCents", "range", PromoMessages.AMOUNT));
        }
        if (maxDiscountCents != null && maxDiscountCents < 100) {
            out.add(new RuleViolation.Violation("maxDiscountCents", "range", PromoMessages.MAX_DISCOUNT));
        }
        if (minSpendCents != null && minSpendCents < 0) {
            out.add(new RuleViolation.Violation("minSpendCents", "range", PromoMessages.MIN_SPEND_RANGE));
        }
        if (startsAt == null || endsAt == null) {
            out.add(new RuleViolation.Violation("endsAt", "required", PromoMessages.WINDOW_REQUIRED));
        } else if (!endsAt.isAfter(startsAt)) {
            out.add(new RuleViolation.Violation("endsAt", "range", PromoMessages.WINDOW));
        }
        if (perCustomerLimit != null && perCustomerLimit < 1) {
            out.add(new RuleViolation.Violation("perCustomerLimit", "range", PromoMessages.LIMIT));
        }
        if (totalLimit != null && totalLimit < 1) {
            out.add(new RuleViolation.Violation("totalLimit", "range", PromoMessages.LIMIT));
        }
        if (!NORTHLINE.equals(fundedBy) && !MERCHANT.equals(fundedBy)) {
            out.add(new RuleViolation.Violation("fundedBy", "required", PromoMessages.FUNDER));
        } else if (MERCHANT.equals(fundedBy) && (merchantId == null || merchantId.isBlank())) {
            out.add(new RuleViolation.Violation("merchantId", "required", PromoMessages.MERCHANT));
        }
        if (appliesTo.isEmpty() || !KINDS.containsAll(appliesTo)) {
            out.add(new RuleViolation.Violation("appliesTo", "required", PromoMessages.APPLIES_TO));
        }
        if (description != null && description.strip().length() > 200) {
            out.add(new RuleViolation.Violation("description", "length", PromoMessages.DESCRIPTION));
        }
        return out;
    }
}
