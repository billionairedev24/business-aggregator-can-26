package ca.northline.payments.application;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-85): the platform account's Stripe balance transactions, for the daily reconciliation against the
 * ledger. stripe-java with a key ({@code GET /v1/balance_transactions}), a fake that mirrors the ledger otherwise.
 */
public interface StripeBalance {

    /** Every balance transaction created in [from, to), oldest first. */
    List<Txn> between(Instant from, Instant to);

    /**
     * @param type Stripe's {@code type} ({@code charge}, {@code payment}, {@code refund}, {@code payment_refund},
     *     {@code adjustment}, {@code transfer}, {@code stripe_fee}, …)
     * @param sourceId the object behind it ({@code ch_…}, {@code py_…}, {@code re_…}, {@code dp_…}); null when none
     * @param amountCents gross, signed (refunds and dispute withdrawals are negative)
     * @param feeCents Stripe's fee on it
     */
    record Txn(
            String id,
            String type,
            @Nullable String sourceId,
            long amountCents,
            long feeCents,
            Instant createdAt) {}
}
