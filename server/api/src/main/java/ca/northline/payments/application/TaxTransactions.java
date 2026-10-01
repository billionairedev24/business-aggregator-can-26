package ca.northline.payments.application;

import ca.northline.payments.application.TaxRepository.Calculation;
import ca.northline.payments.application.TaxRepository.Kind;
import ca.northline.payments.application.TaxRepository.State;
import ca.northline.payments.application.TaxRepository.Transaction;
import ca.northline.payments.domain.CanadianTax;
import ca.northline.payments.domain.CanadianTax.Province;
import ca.northline.payments.domain.Escrow;
import ca.northline.payments.domain.Refund;
import ca.northline.region.api.Markets;
import ca.northline.shared.Ids;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the tax transactions Stripe Tax must hear about, in the transaction that moves the money: a captured sale,
 * a refund paid to the card, a lost chargeback. Each is stored {@code pending} under a unique reference (the dedupe)
 * with {@link TaxSyncRequested} in the same transaction, so the outbox hands it to {@link TaxSyncService} after commit
 * — and the payments job retries whatever didn't get through.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class TaxTransactions {

    private final TaxRepository taxes;
    private final MerchantTiers merchants;
    private final ApplicationEventPublisher events;
    private final Markets markets;
    private final BusinessTime time;

    static String saleReference(String escrowId) {
        return "sale_" + escrowId;
    }

    /**
     * The customer's payment was captured: the tax collected on it is a sale in the province of supply — the checkout
     * calculation's, else the merchant's province.
     */
    void captured(Escrow escrow, Instant at) {
        var calculation = taxes.calculationFor(escrow.getRefType(), escrow.getRefId())
                .filter(c -> c.merchantId().equals(escrow.getMerchantId()));
        if (escrow.getTaxCents() == 0 && calculation.isEmpty()) {
            return; // nothing taxable was reported at checkout
        }
        // the checkout's province of supply, else the merchant's, else the configured default province (region model)
        var province = calculation
                .map(Calculation::province)
                .or(() -> merchants.provinceOf(escrow.getMerchantId()))
                .or(() -> Optional.ofNullable(markets.defaultProvince()).flatMap(Province::of));
        if (province.isEmpty()) {
            log.warn("Tax sale for escrow {} not reported: no province is known", escrow.getId());
            return;
        }
        queue(new Transaction(
                Ids.next(),
                saleReference(escrow.getId()),
                Kind.SALE,
                escrow.getMerchantId(),
                escrow.getId(),
                escrow.getKind(),
                null,
                calculation.map(Calculation::id).orElse(null),
                province.get(),
                escrow.getAmountCents(),
                escrow.getTaxCents(),
                CanadianTax.period(at, time.platform()),
                at,
                State.PENDING,
                null,
                null,
                0));
    }

    /** A refund went back to the card with its share of the tax: reversed against the sale. */
    void refunded(Refund refund, Instant at) {
        var escrowId = refund.getEscrowId();
        if (refund.getTaxCents() == 0 || escrowId == null) {
            return;
        }
        reversal("refund_" + refund.getId(), escrowId, refund.getAmountCents(), refund.getTaxCents(), at);
    }

    /** The bank took the payment back (lost chargeback): {@code taxCents} of it was tax, reversed against the sale. */
    void chargedBack(String disputeId, String escrowId, long amountCents, long taxCents, Instant at) {
        if (taxCents == 0) {
            return;
        }
        reversal("chargeback_" + disputeId, escrowId, amountCents, taxCents, at);
    }

    private void reversal(String reference, String escrowId, long amountCents, long taxCents, Instant at) {
        var sale = taxes.find(saleReference(escrowId)).orElse(null);
        if (sale == null) {
            // captured before the sync existed (or with no tax): Stripe Tax has no sale to reverse
            log.info("Tax reversal {} not reported: no sale was reported for escrow {}", reference, escrowId);
            return;
        }
        queue(new Transaction(
                Ids.next(),
                reference,
                Kind.REVERSAL,
                sale.merchantId(),
                escrowId,
                sale.escrowKind(),
                sale.reference(),
                null,
                sale.province(),
                amountCents,
                taxCents,
                CanadianTax.period(at, time.platform()),
                at,
                State.PENDING,
                null,
                null,
                0));
    }

    private void queue(Transaction transaction) {
        if (taxes.insert(transaction)) {
            events.publishEvent(new TaxSyncRequested(transaction.reference()));
        }
    }
}
