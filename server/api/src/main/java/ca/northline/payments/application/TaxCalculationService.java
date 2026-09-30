package ca.northline.payments.application;

import ca.northline.payments.api.PaymentAuthorizations;
import ca.northline.payments.api.TaxCalculations;
import ca.northline.payments.application.TaxRepository.Calculation;
import ca.northline.payments.domain.CanadianTax.Province;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import java.time.Clock;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tax at checkout: Stripe Tax (or the local fake) prices one job or order line for its province of supply; the
 * calculation is kept (province and amounts only — the postal code is sent to Stripe and dropped) so the sale can be
 * reported from it at capture.
 */
@Service
@RequiredArgsConstructor
@Transactional
class TaxCalculationService implements TaxCalculations {

    static final String PROVINCE = "Choose a Canadian province or territory.";
    static final String POSTAL_CODE = "Enter a Canadian postal code, like T2P 1B5.";
    static final String AMOUNT = "Enter an amount.";

    private static final Pattern POSTAL = Pattern.compile("[A-Z]\\d[A-Z] ?\\d[A-Z]\\d");

    private final TaxRepository taxes;
    private final TaxGateway gateway;
    private final Clock clock;

    @Override
    public Quote calculate(Request request) {
        if (request.amountCents() <= 0) {
            throw RuleViolation.of("amountCents", "range", AMOUNT);
        }
        var province =
                Province.of(request.province()).orElseThrow(() -> RuleViolation.of("province", "format", PROVINCE));
        var postalCode = postalCode(request.postalCode());
        var id = Ids.next();
        var calculated = gateway.calculate(new TaxGateway.Calculate(
                "calculation_" + id,
                request.kind(),
                province,
                postalCode,
                request.amountCents(),
                // every checkout asks afresh; the key only makes stripe-java's own retries safe
                StripeIdempotencyKeys.of("tax-calculation", id)));
        var calculation = new Calculation(
                id,
                calculated.calculation(),
                request.merchantId(),
                request.kind(),
                province,
                request.amountCents(),
                calculated.taxCents(),
                calculated.lines(),
                null,
                null,
                calculated.expiresAt(),
                clock.instant());
        taxes.insertCalculation(calculation);
        return new Quote(
                id,
                calculation.amountCents(),
                calculation.taxCents(),
                province.jurisdiction(),
                calculation.lines().stream()
                        .map(l -> new Line(l.taxType(), l.percent(), l.taxCents()))
                        .toList(),
                calculation.expiresAt());
    }

    /**
     * Checkout opens the PaymentIntent for a quote: the amounts must be the quote's, and a quote prices one job or
     * order line only.
     */
    void use(String calculationId, PaymentAuthorizations.Request request) {
        var calculation = taxes.calculation(calculationId)
                .filter(c -> c.merchantId().equals(request.merchantId()))
                .orElseThrow(() -> RuleViolation.of("taxCalculationId", "format", "Calculate the tax again."));
        if (calculation.amountCents() != request.amountCents() || calculation.taxCents() != request.taxCents()) {
            throw RuleViolation.of("taxCents", "range", "The tax doesn't match its calculation. Calculate it again.");
        }
        if (!taxes.useCalculation(calculationId, request.refType(), request.refId())) {
            throw new Conflict("tax_calculation_used", "This tax calculation is for another booking or order line.");
        }
    }

    private static @Nullable String postalCode(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        var normalised = raw.strip().toUpperCase(Locale.ROOT).replace('-', ' ');
        if (!POSTAL.matcher(normalised).matches()) {
            throw RuleViolation.of("postalCode", "format", POSTAL_CODE);
        }
        return normalised.length() == 6 ? normalised.substring(0, 3) + " " + normalised.substring(3) : normalised;
    }
}
