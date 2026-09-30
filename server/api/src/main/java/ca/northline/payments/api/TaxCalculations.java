package ca.northline.payments.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Checkout (consumer app, orders, booking): the GST/HST/PST/QST on one job or order line, calculated by Stripe Tax
 * (the local fake uses fixed Canadian rates). The caller shows the tax, then passes {@code calculationId} and the same
 * amounts to {@link PaymentAuthorizations#start}; the sale is reported to Stripe Tax from this calculation when the
 * payment is captured. Northline is the marketplace facilitator, so the tax is Northline's to collect and remit.
 */
public interface TaxCalculations {

    /**
     * @param province where the supply happens — the job's address, the delivery address, or the kitchen for pickup —
     *     as a two-letter code ({@code AB}, {@code BC}, {@code ON}, {@code QC}, …)
     * @param postalCode optional, sent to Stripe Tax for this calculation only and never stored
     * @param amountCents the merchant's amount, before tax
     */
    record Request(
            String merchantId,
            EscrowKind kind,
            String province,
            @Nullable String postalCode,
            long amountCents) {}

    /** One tax: {@code gst}, {@code hst}, {@code pst}, {@code qst} or {@code rst}, its rate in % and the amount. */
    record Line(String taxType, BigDecimal percent, long taxCents) {}

    /**
     * @param jurisdiction {@code ab_gst}, {@code bc_gst_pst}, {@code on_hst}, {@code qc_gst_qst}, … (the Studio's tax table)
     * @param expiresAt Stripe keeps a calculation 90 days; the sync recalculates at capture after that
     */
    record Quote(
            String calculationId,
            long amountCents,
            long taxCents,
            String jurisdiction,
            List<Line> lines,
            Instant expiresAt) {

        public Quote {
            lines = List.copyOf(lines);
        }
    }

    Quote calculate(Request request);
}
