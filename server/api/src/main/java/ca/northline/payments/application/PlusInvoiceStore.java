package ca.northline.payments.application;

import java.time.Instant;

/** Outbound port (S-152): {@code payments.plus_invoices}, one row per provider invoice. */
public interface PlusInvoiceStore {

    /** @return false when the invoice was already recorded (a replayed webhook) */
    boolean insert(
            String id,
            String provider,
            String invoiceId,
            String subscriptionId,
            String householdId,
            String plan,
            long amountCents,
            long taxCents,
            Instant paidAt);
}
