package ca.northline.payments.infra;

import ca.northline.payments.application.TaxGateway;
import ca.northline.payments.domain.CanadianTax;
import ca.northline.shared.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Stripe Tax stand-in for {@code local} and {@code test}: the fixed Canadian rates of {@link CanadianTax}, nothing
 * leaves the process. It keeps no state — its ids carry the amounts ({@code taxcalc_local_<amount>_<tax>_<ulid>},
 * {@code tax_local_<total>_<tax>_<ulid>}) — so it answers the same after a restart.
 */
class LocalTaxGateway implements TaxGateway {

    /** Stripe keeps a calculation 90 days. */
    static final Duration CALCULATION_LIFETIME = Duration.ofDays(90);

    private static final Pattern CALCULATION = Pattern.compile("taxcalc_local_(\\d+)_(\\d+)_\\w+");
    private static final Pattern TRANSACTION = Pattern.compile("tax_local_(\\d+)_(\\d+)_\\w+");

    private final Clock clock;

    LocalTaxGateway(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Calculated calculate(Calculate request) {
        var lines = request.province().lines(request.amountCents());
        var tax = CanadianTax.total(lines);
        return new Calculated(
                "taxcalc_local_%d_%d_%s".formatted(request.amountCents(), tax, Ids.next()),
                tax,
                lines,
                clock.instant().plus(CALCULATION_LIFETIME));
    }

    @Override
    public Recorded record(String calculation, String reference, Map<String, String> metadata, String idempotencyKey) {
        var m = CALCULATION.matcher(calculation);
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a local tax calculation: " + calculation);
        }
        var amount = Long.parseLong(m.group(1));
        var tax = Long.parseLong(m.group(2));
        return new Recorded(transactionId(amount + tax, tax), tax);
    }

    @Override
    public Recorded reverse(
            String originalTransaction,
            String reference,
            long totalCents,
            Map<String, String> metadata,
            String idempotencyKey) {
        var m = TRANSACTION.matcher(originalTransaction);
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a local tax transaction: " + originalTransaction);
        }
        var originalTotal = Long.parseLong(m.group(1));
        var originalTax = Long.parseLong(m.group(2));
        if (totalCents > originalTotal) {
            throw new IllegalArgumentException("Reversal of " + totalCents + " exceeds " + originalTotal);
        }
        // Stripe Tax splits a flat reversal in the sale's proportions
        var tax = originalTotal == 0
                ? 0
                : BigDecimal.valueOf(originalTax)
                        .multiply(BigDecimal.valueOf(totalCents))
                        .divide(BigDecimal.valueOf(originalTotal), 0, RoundingMode.HALF_UP)
                        .longValueExact();
        return new Recorded(transactionId(totalCents, tax), tax);
    }

    @Override
    public long taxOf(String transaction) {
        var tax = parsedTax(transaction);
        if (tax == null) {
            throw new IllegalArgumentException("Not a local tax transaction: " + transaction);
        }
        return tax;
    }

    private static @Nullable Long parsedTax(String transaction) {
        var m = TRANSACTION.matcher(transaction);
        return m.matches() ? Long.parseLong(m.group(2)) : null;
    }

    private static String transactionId(long total, long tax) {
        return "tax_local_%d_%d_%s".formatted(total, tax, Ids.next());
    }
}
