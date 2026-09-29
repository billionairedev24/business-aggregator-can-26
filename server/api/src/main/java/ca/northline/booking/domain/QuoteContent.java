package ca.northline.booking.domain;

import ca.northline.booking.domain.QuoteEnums.DepositKind;
import ca.northline.booking.domain.QuoteEnums.LineKind;
import ca.northline.booking.domain.QuoteEnums.Warranty;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a merchant writes in the quote composer (validation-rules.md § Quote). {@link #validate()} enforces every
 * rule and reports all broken ones at once, with the JSON paths of the request ({@code lines[2].unitCents}). The
 * message constants are shared with the Bean Validation annotations of the request.
 */
public record QuoteContent(
        List<QuoteLine> lines,
        String scope,
        @Nullable String exclusions,
        @Nullable Instant proposedAt,
        @Nullable Integer durationMin,
        int validHours,
        Warranty warranty,
        DepositKind depositKind,
        @Nullable Integer depositBps,
        List<String> attachments) {

    public static final String LINE_DESCRIPTION_REQUIRED =
            "Describe this line — customers must see what they're paying for.";
    public static final String LINE_AMOUNT_REQUIRED = "Enter an amount.";
    public static final String SCOPE_REQUIRED = "Describe the scope of work.";
    public static final int LINE_DESCRIPTION_MAX = 160;
    public static final int MAX_LINES = 50;
    public static final int MAX_ATTACHMENTS = 10;
    public static final Set<Integer> VALID_HOURS = Set.of(24, 72, 168, 336);
    // Rules the spec states without a message (validation-rules.md shows "—"); see docs/DECISIONS.md "Operations".
    public static final String LINES_REQUIRED = "Add at least one line.";
    public static final String LINE_DESCRIPTION_TOO_LONG = "At most 160 characters.";
    public static final String LINE_QTY_RANGE = "Quantity must be more than 0.";
    public static final String VALID_HOURS_INVALID = "Choose how long the quote is valid.";
    public static final String DISCOUNT_TOO_LARGE = "Discounts can't be more than the other lines.";
    public static final String DEPOSIT_PCT_RANGE = "Deposit must be between 1 and 100 %.";
    public static final String TOO_MANY_ATTACHMENTS = "At most 10 attachments.";

    public QuoteContent {
        lines = List.copyOf(lines);
        scope = scope.strip();
        exclusions = exclusions == null || exclusions.isBlank() ? null : exclusions.strip();
        attachments = List.copyOf(attachments);
        if (depositKind != DepositKind.PCT) {
            depositBps = null;
        }
    }

    /** Checks every rule (a stored quote is rehydrated without it). @return this */
    public QuoteContent validate() {
        var errors = new ArrayList<Violation>();
        if (lines.isEmpty()) {
            errors.add(new Violation("lines", "required", LINES_REQUIRED));
        }
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            var path = "lines[%d].".formatted(i);
            if (line.description().isEmpty()) {
                errors.add(new Violation(path + "description", "required", LINE_DESCRIPTION_REQUIRED));
            } else if (line.description().length() > LINE_DESCRIPTION_MAX) {
                errors.add(new Violation(path + "description", "length", LINE_DESCRIPTION_TOO_LONG));
            }
            if ((line.kind() != LineKind.DISCOUNT && line.unitCents() <= 0) || line.unitCents() < 0) {
                errors.add(new Violation(path + "unitCents", "positive_unless_discount", LINE_AMOUNT_REQUIRED));
            }
            if (line.qty().signum() <= 0
                    || line.qty().scale() > 2
                    || line.qty().compareTo(new BigDecimal("999999.99")) > 0) {
                errors.add(new Violation(path + "qty", "range", LINE_QTY_RANGE));
            }
        }
        if (scope.isEmpty()) {
            errors.add(new Violation("scope", "required", SCOPE_REQUIRED));
        }
        if (!VALID_HOURS.contains(validHours)) {
            errors.add(new Violation("validHours", "range", VALID_HOURS_INVALID));
        }
        if (depositKind == DepositKind.PCT && (depositBps == null || depositBps < 1 || depositBps > 10_000)) {
            errors.add(new Violation("depositBps", "range", DEPOSIT_PCT_RANGE));
        }
        if (attachments.size() > MAX_ATTACHMENTS) {
            errors.add(new Violation("attachments", "length", TOO_MANY_ATTACHMENTS));
        }
        if (errors.isEmpty() && lines.stream().mapToLong(QuoteLine::amountCents).sum() < 0) {
            errors.add(new Violation("lines", "discount_too_large", DISCOUNT_TOO_LARGE));
        }
        if (!errors.isEmpty()) {
            throw new RuleViolation(errors);
        }
        return this;
    }
}
