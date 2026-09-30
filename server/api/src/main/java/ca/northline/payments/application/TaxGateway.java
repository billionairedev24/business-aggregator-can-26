package ca.northline.payments.application;

import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.domain.CanadianTax;
import ca.northline.payments.domain.CanadianTax.Province;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: Stripe Tax on the platform account (Northline is the seller of record and the marketplace
 * facilitator). A calculation prices the tax at checkout; a transaction reports a captured sale from it; a reversal
 * reports a refund or a lost chargeback against the sale. Selected by {@code northline.tax.provider}: {@code stripe}
 * (stripe-java) or {@code local} (fixed Canadian rates, nothing leaves the process). Every POST carries an
 * idempotency key; metadata holds Northline ids only. Amounts are CAD cents, tax exclusive.
 */
public interface TaxGateway {

    /**
     * @param reference Northline's line reference ({@code booking:<id>}, {@code sale_<escrowId>}, …)
     * @param postalCode sent for this calculation only
     */
    record Calculate(
            String reference,
            EscrowKind kind,
            Province province,
            @Nullable String postalCode,
            long amountCents,
            String idempotencyKey) {}

    /** {@code calculation} = {@code taxcalc_…}. */
    record Calculated(String calculation, long taxCents, List<CanadianTax.Line> lines, Instant expiresAt) {

        public Calculated {
            lines = List.copyOf(lines);
        }
    }

    /**
     * A transaction Stripe Tax recorded ({@code tax_…}); {@code taxCents} is the tax it holds for it (positive, also for
     * a reversal), or null when the provider can't tell.
     */
    record Recorded(String transaction, @Nullable Long taxCents) {}

    Calculated calculate(Calculate request);

    /** Reports a captured sale: a transaction from the calculation, with {@code reference} (unique at Stripe). */
    Recorded record(String calculation, String reference, Map<String, String> metadata, String idempotencyKey);

    /**
     * Reports money given back on {@code originalTransaction}: a partial reversal of {@code totalCents} (amount + tax,
     * positive) — Stripe Tax splits it between amount and tax in the sale's proportions.
     */
    Recorded reverse(
            String originalTransaction,
            String reference,
            long totalCents,
            Map<String, String> metadata,
            String idempotencyKey);

    /** The tax Stripe Tax holds for a transaction (positive; reversals too), for reconciliation. */
    long taxOf(String transaction);
}
