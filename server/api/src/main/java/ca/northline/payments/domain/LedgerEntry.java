package ca.northline.payments.domain;

import ca.northline.shared.Ids;
import java.time.Instant;
import java.util.List;

/**
 * One line of the double-entry ledger ({@code payments.ledger_entries}, append-only). Every posting below balances:
 * Σ debits = Σ credits. Accounts: {@code escrow}, {@code revenue} (Northline's take), {@code stripe_fees},
 * {@code tax_payable}, {@code stripe_balance} (cash at Stripe) and {@code merchant:<id>} (owed to the merchant — its
 * credit balance is what the Studio calls "released").
 *
 * <p>Mobile gaps part 2: {@code promotions} (Northline-funded promo codes: Northline's cost, debited when it tops the
 * merchant up at release), {@code points_redeemed} (what points paid, Northline's money, debited at capture and
 * credited back with refunds), {@code courier:<user id>} (tips owed to a courier; {@code courier_tips} holds a tip
 * until its delivery names the courier). A merchant-funded code shows in the merchant's own account: credited the full
 * price, debited the discount.
 */
public record LedgerEntry(
        String id, String account, long debitCents, long creditCents, String refType, String refId, Instant at) {

    public static final String ESCROW = "escrow";
    public static final String REVENUE = "revenue";
    public static final String STRIPE_FEES = "stripe_fees";
    public static final String TAX_PAYABLE = "tax_payable";
    public static final String STRIPE_BALANCE = "stripe_balance";
    /** S-57: food tips owed to couriers ("100% goes to them") until courier payouts exist. */
    public static final String COURIER_TIPS = "courier_tips";
    /** Northline-funded promo codes (an expense: its debit balance is what codes cost Northline). */
    public static final String PROMOTIONS = "promotions";
    /** What points paid at checkout (Northline's money; refunds give their share back). */
    public static final String POINTS = "points_redeemed";
    /** The reference type of a courier tip's own postings (after delivery, allocation, refund). */
    public static final String TIP = "courier_tip";
    /** S-78: the reference type of a delivery fee's postings (the order id), as on its PaymentIntent. */
    public static final String DELIVERY_FEE = "order_delivery";

    public static String merchant(String merchantId) {
        return "merchant:" + merchantId;
    }

    /** Tips owed to one courier (by their user id). */
    public static String courier(String courierUserId) {
        return "courier:" + courierUserId;
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

    /** The customer's payment is captured into escrow (tax is owed to the CRA); points pay their part. */
    public static List<LedgerEntry> captured(Escrow e, Instant at) {
        return nonZero(
                debit(STRIPE_BALANCE, e.capturedCents(), "escrow", e.getId(), at),
                debit(POINTS, e.getPointsCents(), "escrow", e.getId(), at),
                credit(ESCROW, e.getAmountCents(), "escrow", e.getId(), at),
                credit(TAX_PAYABLE, e.getTaxCents() + e.getPlatformTaxCents(), "escrow", e.getId(), at),
                credit(REVENUE, e.getPlatformFeeCents(), "escrow", e.getId(), at),
                credit(COURIER_TIPS, e.getTipCents(), "escrow", e.getId(), at));
    }

    /**
     * S-78: an order's delivery fee is captured (no escrow: it is Northline's from the start) — the fee is revenue, its
     * GST/HST is owed to the CRA.
     */
    public static List<LedgerEntry> deliveryFeeCaptured(
            String orderId, long totalCents, long taxCents, long tipCents, Instant at) {
        return nonZero(
                debit(STRIPE_BALANCE, totalCents, DELIVERY_FEE, orderId, at),
                credit(TAX_PAYABLE, taxCents, DELIVERY_FEE, orderId, at),
                credit(COURIER_TIPS, tipCents, DELIVERY_FEE, orderId, at),
                credit(REVENUE, totalCents - taxCents - tipCents, DELIVERY_FEE, orderId, at));
    }

    /**
     * Escrow → merchant balance (net) + Northline's fee. A Northline-funded code: Northline tops the merchant up from
     * {@code promotions}, so the merchant gets the full price less the fee. A merchant-funded code: the merchant's
     * account shows the full price and the discount it funded.
     */
    public static List<LedgerEntry> released(Escrow e, Instant at) {
        var merchant = merchant(e.getMerchantId());
        return nonZero(
                debit(ESCROW, e.getAmountCents(), "escrow", e.getId(), at),
                debit(PROMOTIONS, e.northlineDiscountCents(), "escrow", e.getId(), at),
                debit(merchant, e.merchantDiscountCents(), "escrow", e.getId(), at),
                credit(merchant, e.netCents() + e.merchantDiscountCents(), "escrow", e.getId(), at),
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
     * back too and is no longer owed to the CRA ({@code tax_payable}, S-21). Points' share goes back to the wallet, not
     * the card ({@code points_redeemed}); after a release, the refunded share of a Northline top-up comes back from the
     * merchant ({@code promotions}).
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
        var promoReturn = escrowReleased && r.getChargedTo() == ChargedTo.MERCHANT ? r.getPromoReturnCents() : 0;
        return nonZero(
                debit(from, r.getAmountCents(), "refund", r.getId(), at),
                debit(TAX_PAYABLE, r.getTaxCents(), "refund", r.getId(), at),
                debit(merchant(r.getMerchantId()), promoReturn, "refund", r.getId(), at),
                credit(STRIPE_BALANCE, r.cardCents(), "refund", r.getId(), at),
                credit(POINTS, r.getPointsCents(), "refund", r.getId(), at),
                credit(PROMOTIONS, promoReturn, "refund", r.getId(), at));
    }

    /** A tip paid after the delivery: straight to the courier (no tax: a tip is not a taxable supply). */
    public static List<LedgerEntry> tipCaptured(String tipId, String courierUserId, long cents, Instant at) {
        return nonZero(
                debit(STRIPE_BALANCE, cents, TIP, tipId, at), credit(courier(courierUserId), cents, TIP, tipId, at));
    }

    /** A checkout tip's delivery named its courier: from the pool to them. */
    public static List<LedgerEntry> tipAllocated(String tipId, String courierUserId, long cents, Instant at) {
        return nonZero(
                debit(COURIER_TIPS, cents, TIP, tipId, at), credit(courier(courierUserId), cents, TIP, tipId, at));
    }

    /** A tip refunded to the card (console, defined cases only): from the courier, or the pool when unassigned. */
    public static List<LedgerEntry> tipRefunded(
            String tipId, @org.jspecify.annotations.Nullable String courierUserId, long cents, Instant at) {
        return nonZero(
                debit(courierUserId == null ? COURIER_TIPS : courier(courierUserId), cents, TIP, tipId, at),
                credit(STRIPE_BALANCE, cents, TIP, tipId, at));
    }
}
