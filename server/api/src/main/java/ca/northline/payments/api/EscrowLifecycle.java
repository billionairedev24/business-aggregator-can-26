package ca.northline.payments.api;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Holding and releasing money — called by booking (quote accepted → hold; job completed → release clock; customer
 * sign-off → release), orders (line placed / delivered / confirmed) and food (handoff). Every method is idempotent on
 * {@code (refType, refId)} so it can be called from retried {@code @ApplicationModuleListener}s.
 */
public interface EscrowLifecycle {

    /**
     * The payment for one job or order line is authorized (Stripe PaymentIntent, manual capture).
     *
     * @param refType {@code booking} | {@code order_line}
     * @param amountCents what the merchant earns on, before tax
     * @param taxCents GST/HST collected on top (Northline remits it as marketplace facilitator)
     * @param customerName display form ("D. Kowalski") shown in the Studio ledger
     * @param source {@code search} | {@code repeat} | {@code embed} | {@code referral}, or null when unknown
     */
    record Hold(
            String merchantId,
            EscrowKind kind,
            String refType,
            String refId,
            long amountCents,
            long taxCents,
            String customerId,
            String customerName,
            String label,
            @Nullable String orderNumber,
            @Nullable String listingId,
            @Nullable String listingName,
            @Nullable String source,
            String stripePaymentIntent,
            Instant occurredAt,
            @Nullable PlatformCharges platform,
            @Nullable Discount discount) {

        /** Without a promo code or points (the S-57 shape). */
        public Hold(
                String merchantId,
                EscrowKind kind,
                String refType,
                String refId,
                long amountCents,
                long taxCents,
                String customerId,
                String customerName,
                String label,
                @Nullable String orderNumber,
                @Nullable String listingId,
                @Nullable String listingName,
                @Nullable String source,
                String stripePaymentIntent,
                Instant occurredAt,
                @Nullable PlatformCharges platform) {
            this(
                    merchantId,
                    kind,
                    refType,
                    refId,
                    amountCents,
                    taxCents,
                    customerId,
                    customerName,
                    label,
                    orderNumber,
                    listingId,
                    listingName,
                    source,
                    stripePaymentIntent,
                    occurredAt,
                    platform,
                    null);
        }

        /** Without platform charges (the pre-S-57 shape). */
        public Hold(
                String merchantId,
                EscrowKind kind,
                String refType,
                String refId,
                long amountCents,
                long taxCents,
                String customerId,
                String customerName,
                String label,
                @Nullable String orderNumber,
                @Nullable String listingId,
                @Nullable String listingName,
                @Nullable String source,
                String stripePaymentIntent,
                Instant occurredAt) {
            this(
                    merchantId,
                    kind,
                    refType,
                    refId,
                    amountCents,
                    taxCents,
                    customerId,
                    customerName,
                    label,
                    orderNumber,
                    listingId,
                    listingName,
                    source,
                    stripePaymentIntent,
                    occurredAt,
                    null,
                    null);
        }

        public long platformTotal() {
            return platform == null ? 0 : platform.totalCents();
        }

        /** What the card was authorized for: the amount, its tax and Northline's charges, less what points pay. */
        public long cardCents() {
            return amountCents + taxCents + platformTotal() - (discount == null ? 0 : discount.pointsCents());
        }
    }

    /**
     * Mobile gaps part 2: what a promo code and points took off this escrow. {@code amountCents} of the hold is already
     * the amount after the code (the tax was computed on it).
     *
     * @param codeCents the promo code's discount on this line
     * @param fundedBy {@code northline} (Northline tops the merchant up at release: ledger {@code promotions}) or
     *     {@code merchant} (the business sold for less); null without a code
     * @param pointsCents what points pay of the amount and tax (ledger {@code points_redeemed}, Northline's money)
     */
    record Discount(long codeCents, @Nullable String fundedBy, long pointsCents) {
        public Discount {
            if (codeCents < 0 || pointsCents < 0) {
                throw new IllegalArgumentException("discounts can't be negative");
            }
            if ((codeCents > 0) != (fundedBy != null)) {
                throw new IllegalArgumentException("a code's discount needs its funder");
            }
            if (fundedBy != null && !fundedBy.equals("northline") && !fundedBy.equals("merchant")) {
                throw new IllegalArgumentException("funded by northline or merchant: " + fundedBy);
            }
        }

        public boolean any() {
            return codeCents > 0 || pointsCents > 0;
        }
    }

    /**
     * Northline's own charges on the same card payment (S-57 food): the courier and service fees ({@code feeCents},
     * Northline's revenue), their GST/HST ({@code feeTaxCents}, owed to the CRA) and the courier's tip
     * ({@code tipCents}, owed to the courier — "100% goes to them"). Captured with the escrow, never transferred to the
     * merchant, not part of the take rate.
     */
    record PlatformCharges(long feeCents, long feeTaxCents, long tipCents) {
        public PlatformCharges {
            if (feeCents < 0 || feeTaxCents < 0 || tipCents < 0) {
                throw new IllegalArgumentException("platform charges can't be negative");
            }
        }

        public long totalCents() {
            return feeCents + feeTaxCents + tipCents;
        }
    }

    /** Records the hold; returns the escrow id (the existing one when already held). */
    String hold(Hold hold);

    /** The work was completed / delivered / handed off: starts the release clock of the escrow's kind. */
    void fulfilled(String refType, String refId, Instant at);

    /** Like {@link #fulfilled} but a no-op when nothing is held for the reference (work paid without escrow). */
    boolean fulfilledIfHeld(String refType, String refId, Instant at);

    /** The customer signed off / confirmed delivery: releases now. */
    void confirmed(String refType, String refId, Instant at);

    /** Like {@link #confirmed} but a no-op when nothing is held for the reference. */
    boolean confirmedIfHeld(String refType, String refId, Instant at);

    /**
     * S-78: the order was delivered (or the customer confirmed it), so its delivery fee — the manual-capture
     * PaymentIntent checkout opened for Northline ({@code order_delivery}, S-51) — is captured: Northline's revenue and
     * the fee's GST/HST. No escrow and no release: the fee is never transferred to a merchant. Idempotent; false when
     * the order has no authorized delivery-fee hold (free delivery, already captured or canceled).
     */
    boolean captureDeliveryFee(String orderId, Instant at);
}
