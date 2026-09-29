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
            Instant occurredAt) {}

    /** Records the hold; returns the escrow id (the existing one when already held). */
    String hold(Hold hold);

    /** The work was completed / delivered / handed off: starts the release clock of the escrow's kind. */
    void fulfilled(String refType, String refId, Instant at);

    /** Like {@link #fulfilled} but a no-op when nothing is held for the reference (work paid without escrow). */
    boolean fulfilledIfHeld(String refType, String refId, Instant at);

    /** The customer signed off / confirmed delivery: releases now. */
    void confirmed(String refType, String refId, Instant at);
}
