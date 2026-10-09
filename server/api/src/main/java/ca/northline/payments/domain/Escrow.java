package ca.northline.payments.domain;

import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.api.EscrowReleased;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import java.time.Instant;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * Money held for one job or order line (aggregate root). The fee is fixed when the money is held, at the merchant's
 * tier take rate; the release clock starts when the work is fulfilled ({@link EscrowKind#releaseAt}) and a customer
 * sign-off releases at once. A dispute or refund case puts it on hold ({@link EscrowState#DISPUTED}).
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Escrow {
    @EqualsAndHashCode.Include
    @ToString.Include
    private final String id;

    private @Nullable String paymentIntentId;
    private final String refType;
    private final String refId;
    private final String merchantId;
    private final EscrowKind kind;
    private final long amountCents;
    private final long feeCents;
    private final int takeRateBps;
    private final long taxCents;
    /** S-57: Northline's own charges captured with this escrow (fees, their tax, the courier's tip); 0 elsewhere. */
    private final long platformFeeCents;

    private final long platformTaxCents;
    private final long tipCents;
    // Mobile gaps part 2: a promo code's discount on this line (amountCents is already after it), who funds it
    // ("northline" or "merchant", null without a code) and what points paid of amount + tax (Northline's money).
    private final long discountCents;

    private final @Nullable String discountFundedBy;
    private final long pointsCents;

    private final String label;
    private final @Nullable String orderNumber;
    private final @Nullable String customerId;
    private final @Nullable String customerName;
    private final @Nullable String listingId;
    private final @Nullable String listingName;
    private final @Nullable String source;
    private final Instant occurredAt;
    private @Nullable Instant fulfilledAt;
    private @Nullable Instant releaseAt;
    private @Nullable Instant releasedAt;

    @ToString.Include
    private EscrowState state;

    private final Instant createdAt;
    private final @Nullable Integer version;

    /** A new hold at the merchant's take rate (the tier's, unless the merchant has its own). */
    public static Escrow hold(EscrowLifecycle.Hold hold, int takeRateBps, String paymentIntentId, Instant now) {
        var discount = hold.discount();
        var code = discount == null ? 0 : discount.codeCents();
        var funder = discount == null || code == 0 ? null : discount.fundedBy();
        // Northline-funded: the merchant's sale is the full price, so the take rate is on it too (as without the code)
        var feeBase = hold.amountCents() + ("northline".equals(funder) ? code : 0);
        return Escrow.builder()
                .id(Ids.next())
                .paymentIntentId(paymentIntentId)
                .refType(hold.refType())
                .refId(hold.refId())
                .merchantId(hold.merchantId())
                .kind(hold.kind())
                .amountCents(hold.amountCents())
                .takeRateBps(takeRateBps)
                .feeCents(Fees.percentOf(feeBase, takeRateBps))
                .taxCents(hold.taxCents())
                .platformFeeCents(hold.platform() == null ? 0 : hold.platform().feeCents())
                .platformTaxCents(hold.platform() == null ? 0 : hold.platform().feeTaxCents())
                .tipCents(hold.platform() == null ? 0 : hold.platform().tipCents())
                .discountCents(code)
                .discountFundedBy(funder)
                .pointsCents(discount == null ? 0 : discount.pointsCents())
                .label(hold.label())
                .orderNumber(hold.orderNumber())
                .customerId(hold.customerId())
                .customerName(hold.customerName())
                .listingId(hold.listingId())
                .listingName(hold.listingName())
                .source(hold.source())
                .occurredAt(hold.occurredAt())
                .state(EscrowState.HELD)
                .createdAt(now)
                .build();
    }

    /**
     * What the card is charged at capture: the merchant's amount, its tax and Northline's own charges, less what
     * points paid.
     */
    public long capturedCents() {
        return amountCents + taxCents + platformFeeCents + platformTaxCents + tipCents - pointsCents;
    }

    /** The top-up Northline pays the merchant at release for a Northline-funded code (0 otherwise). */
    public long northlineDiscountCents() {
        return "northline".equals(discountFundedBy) ? discountCents : 0;
    }

    /** The discount the merchant funded (0 otherwise). */
    public long merchantDiscountCents() {
        return "merchant".equals(discountFundedBy) ? discountCents : 0;
    }

    /** The merchant's sale: what the customer's amount and a Northline-funded code together pay. */
    public long saleCents() {
        return amountCents + northlineDiscountCents();
    }

    public long netCents() {
        return saleCents() - feeCents;
    }

    /** Completed / delivered / handed off: the release clock of the kind starts (food releases at once). */
    public void fulfil(Instant at) {
        if (state == EscrowState.RELEASED || state == EscrowState.REFUNDED || fulfilledAt != null) {
            return;
        }
        fulfilledAt = at;
        releaseAt = kind.releaseAt(at);
    }

    /** Customer sign-off / delivery confirmation: releases now (or on the next run of the release job). */
    public void confirm(Instant at) {
        if (state == EscrowState.RELEASED || state == EscrowState.REFUNDED) {
            return;
        }
        if (fulfilledAt == null) {
            fulfilledAt = at;
        }
        if (releaseAt == null || releaseAt.isAfter(at)) {
            releaseAt = at;
        }
    }

    public boolean releasable(Instant now) {
        return state == EscrowState.HELD && releaseAt != null && !releaseAt.isAfter(now);
    }

    /** Moves the net to the merchant's balance. Only when due and not on hold. */
    public Optional<EscrowReleased> release(Instant now) {
        if (!releasable(now)) {
            return Optional.empty();
        }
        return Optional.of(markReleased(now));
    }

    /** A dispute or refund case opened on it: payouts of this money pause. */
    public void putOnHold() {
        if (state == EscrowState.HELD) {
            state = EscrowState.DISPUTED;
        }
    }

    /** The case closed in the merchant's favour before the release: the normal release clock applies again. */
    public void resume() {
        if (state == EscrowState.DISPUTED) {
            state = EscrowState.HELD;
        }
    }

    /**
     * The case closed without taking all of the money: release now (the merchant won, or a partial refund is charged to
     * their balance afterwards).
     */
    public EscrowReleased releaseAfterCase(Instant now) {
        if (state != EscrowState.DISPUTED && state != EscrowState.HELD) {
            throw new Conflict("escrow_not_on_hold", "This payment is no longer on hold.");
        }
        if (fulfilledAt == null) {
            fulfilledAt = now;
        }
        releaseAt = now;
        return markReleased(now);
    }

    /** The whole amount goes back to the customer; it never reaches the merchant's balance. */
    public void refundInFull() {
        if (state == EscrowState.RELEASED) {
            throw new Conflict("escrow_released", "This payment was already released.");
        }
        state = EscrowState.REFUNDED;
    }

    /** Whether the money is still only authorized (not captured) and the authorization must be kept alive. */
    public boolean awaitingCapture() {
        return fulfilledAt == null && (state == EscrowState.HELD || state == EscrowState.DISPUTED);
    }

    /** A renewed authorization replaced the one that was about to lapse ({@link AuthorizationWindow}). */
    public void reauthorized(String newPaymentIntentId) {
        if (!awaitingCapture()) {
            throw new Conflict("escrow_not_authorized", "This payment is no longer waiting for capture.");
        }
        paymentIntentId = newPaymentIntentId;
    }

    public boolean released() {
        return state == EscrowState.RELEASED;
    }

    private EscrowReleased markReleased(Instant now) {
        state = EscrowState.RELEASED;
        releasedAt = now;
        return new EscrowReleased(Ids.next(), now, id, merchantId, refType, refId, amountCents, feeCents, netCents());
    }
}
