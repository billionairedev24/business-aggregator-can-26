package ca.northline.payments.application;

import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.domain.CanadianTax;
import ca.northline.payments.domain.CanadianTax.Province;
import ca.northline.shared.CodedEnum;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: tax calculations ({@code payments.tax_calculations}), the tax transactions reported to Stripe Tax
 * ({@code payments.tax_transactions}, deduplicated by {@code reference}) and the Studio's read model
 * ({@code payments.tax_jurisdiction_totals}).
 */
public interface TaxRepository {

    record Calculation(
            String id,
            String stripeCalculation,
            String merchantId,
            EscrowKind kind,
            Province province,
            long amountCents,
            long taxCents,
            List<CanadianTax.Line> lines,
            @Nullable String refType,
            @Nullable String refId,
            Instant expiresAt,
            Instant createdAt) {

        public Calculation {
            lines = List.copyOf(lines);
        }
    }

    enum Kind implements CodedEnum {
        SALE,
        REVERSAL
    }

    enum State implements CodedEnum {
        PENDING,
        RECORDED,
        FAILED
    }

    /** A row of {@code payments.tax_transactions}. */
    record Transaction(
            String id,
            String reference,
            Kind kind,
            String merchantId,
            String escrowId,
            EscrowKind escrowKind,
            @Nullable String originalReference,
            @Nullable String calculationId,
            Province province,
            long amountCents,
            long taxCents,
            String period,
            Instant occurredAt,
            State state,
            @Nullable String stripeTransaction,
            @Nullable Long stripeTaxCents,
            int attempts) {

        public String jurisdiction() {
            return province.jurisdiction();
        }
    }

    /** A row of the read model: one merchant, quarter and jurisdiction. */
    record TotalsKey(String merchantId, String period, String jurisdiction) {}

    void insertCalculation(Calculation calculation);

    Optional<Calculation> calculation(String id);

    /** The calculation checkout used for a job / order line (the newest when there were several). */
    Optional<Calculation> calculationFor(String refType, String refId);

    /** Ties a calculation to the job / order line it priced; false when it priced another one already. */
    boolean useCalculation(String id, String refType, String refId);

    /** Inserts a pending transaction; false when one with the same reference exists (dedupe). */
    boolean insert(Transaction transaction);

    /** Locks the row for reporting (waits for another reporter of the same reference). */
    Optional<Transaction> lock(String reference);

    Optional<Transaction> find(String reference);

    void recorded(
            String reference,
            String stripeTransaction,
            @Nullable Long stripeTaxCents,
            @Nullable String calculationId,
            Instant at);

    void failed(String reference, String error);

    /** Pending or failed transactions with fewer than {@code maxAttempts}, oldest first. */
    List<String> pending(int maxAttempts, int limit);

    /** Recorded transactions of a quarter not compared with Stripe Tax yet. */
    List<Transaction> unreconciled(String period, int limit);

    void reconciled(String reference, long stripeTaxCents, Instant at);

    /**
     * Recomputes the sync's part of one read-model row from the recorded transactions: sales and reversals of the
     * jurisdiction, and {@code collected_cents} moved by the change in their difference (a row that existed before the
     * sync keeps its amount as a base). Idempotent.
     */
    void refreshTotals(TotalsKey key, Instant at);

    /** Every row the recorded transactions of a quarter feed. */
    List<TotalsKey> totalsOf(String period);

    /** The province the merchant operates in ({@code merchants.merchants.province}, read-only). */
    Optional<Province> merchantProvince(String merchantId);
}
