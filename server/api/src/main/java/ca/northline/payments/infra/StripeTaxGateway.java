package ca.northline.payments.infra;

import ca.northline.payments.application.TaxGateway;
import ca.northline.payments.domain.CanadianTax;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.tax.Calculation;
import com.stripe.model.tax.Transaction;
import com.stripe.model.tax.TransactionLineItem;
import com.stripe.net.RequestOptions;
import com.stripe.param.tax.CalculationCreateParams;
import com.stripe.param.tax.TransactionCreateFromCalculationParams;
import com.stripe.param.tax.TransactionCreateReversalParams;
import com.stripe.param.tax.TransactionLineItemListParams;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Stripe Tax through stripe-java (API version pinned by {@code StripeClients}), on the platform account — Northline
 * sells as the marketplace facilitator, so its registrations (GST/HST, and PST/QST where it registers) apply.
 * Calculation: one tax-exclusive CAD line with the kind's product tax code, the place of supply as the customer's
 * {@code shipping} address (country, province and, at checkout, the postal code). A sale is a transaction from the
 * calculation; money given back is a partial reversal with a flat (negative) amount, which Stripe splits between
 * amount and tax in the sale's proportions. Every POST carries an {@code Idempotency-Key}; metadata is Northline ids.
 * Written against Stripe's documented API and tested with stripe-mock only — never run against a real account.
 */
class StripeTaxGateway implements TaxGateway {

    private static final String CAD = "cad";
    private static final String LINE_ITEMS = "line_items";

    private final StripeClient stripe;
    private final TaxProperties properties;
    private final Clock clock;

    StripeTaxGateway(StripeClient stripe, TaxProperties properties, Clock clock) {
        this.stripe = stripe;
        this.properties = properties;
        this.clock = clock;
    }

    @FunctionalInterface
    private interface StripeCall<T> {
        T run() throws StripeException;
    }

    private static <T> T call(String what, StripeCall<T> call) {
        try {
            return call.run();
        } catch (StripeException e) {
            throw new StripeConnectGateway.StripeCallFailed(what, e);
        }
    }

    private static RequestOptions key(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    @Override
    public Calculated calculate(Calculate request) {
        var address = CalculationCreateParams.CustomerDetails.Address.builder()
                .setCountry("CA")
                .setState(request.province().name());
        var postalCode = request.postalCode();
        if (postalCode != null) {
            address.setPostalCode(postalCode);
        }
        var params = CalculationCreateParams.builder()
                .setCurrency(CAD)
                .addLineItem(CalculationCreateParams.LineItem.builder()
                        .setAmount(request.amountCents())
                        .setReference(request.reference())
                        .setTaxCode(properties.taxCode(request.kind()))
                        .setTaxBehavior(CalculationCreateParams.LineItem.TaxBehavior.EXCLUSIVE)
                        .build())
                .setCustomerDetails(CalculationCreateParams.CustomerDetails.builder()
                        .setAddress(address.build())
                        .setAddressSource(CalculationCreateParams.CustomerDetails.AddressSource.SHIPPING)
                        .build())
                .build();
        var calculation = call(
                "tax calculation",
                () -> stripe.v1().tax().calculations().create(params, key(request.idempotencyKey())));
        return new Calculated(
                calculation.getId(),
                Objects.requireNonNullElse(calculation.getTaxAmountExclusive(), 0L),
                lines(calculation),
                expiresAt(calculation));
    }

    /** Stripe's breakdown per tax (GST, HST, PST, QST, RST); taxes Stripe doesn't collect (not registered) are left out. */
    static List<CanadianTax.Line> lines(Calculation calculation) {
        var breakdown = calculation.getTaxBreakdown();
        if (breakdown == null) {
            return List.of();
        }
        return breakdown.stream()
                .filter(b -> b.getAmount() != null && b.getAmount() > 0 && b.getTaxRateDetails() != null)
                .map(b -> new CanadianTax.Line(
                        Objects.requireNonNullElse(b.getTaxRateDetails().getTaxType(), "gst"),
                        new BigDecimal(
                                Objects.requireNonNullElse(b.getTaxRateDetails().getPercentageDecimal(), "0")),
                        b.getAmount()))
                .toList();
    }

    private Instant expiresAt(Calculation calculation) {
        var at = calculation.getExpiresAt();
        return at == null ? clock.instant().plus(LocalTaxGateway.CALCULATION_LIFETIME) : Instant.ofEpochSecond(at);
    }

    @Override
    public Recorded record(String calculation, String reference, Map<String, String> metadata, String idempotencyKey) {
        var params = TransactionCreateFromCalculationParams.builder()
                .setCalculation(calculation)
                .setReference(reference)
                .putAllMetadata(metadata)
                .addExpand(LINE_ITEMS)
                .build();
        var transaction = call(
                "tax transaction",
                () -> stripe.v1().tax().transactions().createFromCalculation(params, key(idempotencyKey)));
        return new Recorded(transaction.getId(), tax(transaction));
    }

    @Override
    public Recorded reverse(
            String originalTransaction,
            String reference,
            long totalCents,
            Map<String, String> metadata,
            String idempotencyKey) {
        var params = TransactionCreateReversalParams.builder()
                .setMode(TransactionCreateReversalParams.Mode.PARTIAL)
                .setOriginalTransaction(originalTransaction)
                .setReference(reference)
                .setFlatAmount(-totalCents)
                .putAllMetadata(metadata)
                .addExpand(LINE_ITEMS)
                .build();
        var transaction = call(
                "tax reversal", () -> stripe.v1().tax().transactions().createReversal(params, key(idempotencyKey)));
        return new Recorded(transaction.getId(), tax(transaction));
    }

    @Override
    public long taxOf(String transaction) {
        var items = call(
                "tax transaction line items",
                () -> stripe.v1()
                        .tax()
                        .transactions()
                        .lineItems()
                        .list(
                                transaction,
                                TransactionLineItemListParams.builder()
                                        .setLimit(100L)
                                        .build()));
        return sum(items.getData());
    }

    /** Σ |amount_tax| of the (expanded) line items; null when Stripe didn't include them. */
    private static @Nullable Long tax(Transaction transaction) {
        var items = transaction.getLineItems();
        return items == null || items.getData() == null ? null : sum(items.getData());
    }

    private static long sum(@Nullable List<TransactionLineItem> items) {
        return items == null
                ? 0
                : items.stream()
                        .mapToLong(i -> Math.abs(Objects.requireNonNullElse(i.getAmountTax(), 0L)))
                        .sum();
    }
}
