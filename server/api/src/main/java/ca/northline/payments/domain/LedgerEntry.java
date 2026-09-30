package ca.northline.payments.domain;

import ca.northline.shared.Ids;
import java.time.Instant;
import java.util.List;

/**
 * One line of the double-entry ledger ({@code payments.ledger_entries}, append-only). Every posting below balances:
 * Σ debits = Σ credits. Accounts: {@code escrow}, {@code revenue} (Northline's take), {@code stripe_fees},
 * {@code tax_payable}, {@code stripe_balance} (cash at Stripe) and {@code merchant:<id>} (owed to the merchant — its
 * credit balance is what the Studio calls "released").
 */
public record LedgerEntry(
        String id, String account, long debitCents, long creditCents, String refType, String refId, Instant at) {

    public static final String ESCROW = "escrow";
    public static final String REVENUE = "revenue";
    public static final String STRIPE_FEES = "stripe_fees";
    public static final String TAX_PAYABLE = "tax_payable";
    public static final String STRIPE_BALANCE = "stripe_balance";

    public static String merchant(String merchantId) {
        return "merchant:" + merchantId;
    }

    private static LedgerEntry debit(String account, long cents, String refType, String refId, Instant at) {
        return new LedgerEntry(Ids.next(), account, cents, 0, refType, refId, at);
    }

    private static LedgerEntry credit(String account, long cents, String refType, String refId, Instant at) {
        return new LedgerEntry(Ids.next(), account, 0, cents, refType, refId, at);
    }

    private static List<LedgerEntry> nonZero(LedgerEntry... entries) {
        return java.util.Arrays.stream(entries)
                .filter(e -> e.debitCents() != 0 || e.creditCents() != 0)
                .toList();
    }

    /** The customer's payment is captured into escrow (tax is owed to the CRA). */
    public static List<LedgerEntry> captured(Escrow e, Instant at) {
        return nonZero(
                debit(STRIPE_BALANCE, e.getAmountCents() + e.getTaxCents(), "escrow", e.getId(), at),
                credit(ESCROW, e.getAmountCents(), "escrow", e.getId(), at),
                credit(TAX_PAYABLE, e.getTaxCents(), "escrow", e.getId(), at));
    }

    /** Escrow → merchant balance (net) + Northline's fee. */
    public static List<LedgerEntry> released(Escrow e, Instant at) {
        return nonZero(
                debit(ESCROW, e.getAmountCents(), "escrow", e.getId(), at),
                credit(merchant(e.getMerchantId()), e.netCents(), "escrow", e.getId(), at),
                credit(REVENUE, e.getFeeCents(), "escrow", e.getId(), at));
    }

    /** Merchant balance → their bank (instant payouts pay the 1 % fee out of the amount). */
    public static List<LedgerEntry> paidOut(Payout p) {
        return nonZero(
                debit(merchant(p.getMerchantId()), p.getAmountCents(), "payout", p.getId(), p.getCreatedAt()),
                credit(STRIPE_BALANCE, p.netCents(), "payout", p.getId(), p.getCreatedAt()),
                credit(STRIPE_FEES, p.getFeeCents(), "payout", p.getId(), p.getCreatedAt()));
    }

    /** Stripe returned a payout (failed or canceled): the exact reverse of {@link #paidOut}. */
    public static List<LedgerEntry> payoutReturned(Payout p, Instant at) {
        return nonZero(
                debit(STRIPE_BALANCE, p.netCents(), "payout", p.getId(), at),
                debit(STRIPE_FEES, p.getFeeCents(), "payout", p.getId(), at),
                credit(merchant(p.getMerchantId()), p.getAmountCents(), "payout", p.getId(), at));
    }

    /**
     * A card dispute was lost: the bank took {@code merchantCents} + {@code platformCents} back from Stripe. The merchant
     * carries up to the escrow amount — from their balance when the money was released, else from escrow — and the
     * rest (the tax part) is tax no longer owed ({@code tax_payable}, up to what the sale collected, S-21); anything
     * beyond that Northline carries.
     */
    public static List<LedgerEntry> chargedBack(
            Escrow e, String disputeId, long merchantCents, long platformCents, boolean released, Instant at) {
        var tax = Math.min(platformCents, e.getTaxCents());
        return nonZero(
                debit(released ? merchant(e.getMerchantId()) : ESCROW, merchantCents, "dispute", disputeId, at),
                debit(TAX_PAYABLE, tax, "dispute", disputeId, at),
                debit(REVENUE, platformCents - tax, "dispute", disputeId, at),
                credit(STRIPE_BALANCE, merchantCents + platformCents, "dispute", disputeId, at));
    }

    /**
     * A refund is paid back to the customer. Who funds it: the platform (goodwill credits), the escrow (money that never
     * reached the merchant) or the merchant's balance (money already released). The GST/HST on the refunded part goes
     * back too and is no longer owed to the CRA ({@code tax_payable}, S-21).
     */
    public static List<LedgerEntry> refunded(Refund r, boolean escrowReleased, Instant at) {
        String from;
        if (r.getChargedTo() == ChargedTo.PLATFORM) {
            from = REVENUE;
        } else if (escrowReleased) {
            from = merchant(r.getMerchantId());
        } else {
            from = ESCROW;
        }
        return nonZero(
                debit(from, r.getAmountCents(), "refund", r.getId(), at),
                debit(TAX_PAYABLE, r.getTaxCents(), "refund", r.getId(), at),
                credit(STRIPE_BALANCE, r.cardCents(), "refund", r.getId(), at));
    }
}
