package ca.northline.payments.application;

import ca.northline.payments.api.PaymentReauthorizationRequired;
import ca.northline.payments.application.PaymentGateway.IntentStatus;
import ca.northline.shared.Ids;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps Northline's mirror of PaymentIntents, refunds and transfers in step with Stripe's webhooks. A status only moves
 * forward ({@link #advances}), so a late {@code amount_capturable_updated} never undoes a capture.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class StripeChargeSync {

    private final EscrowRepository escrows;
    private final CaseRepository cases;
    private final ApplicationEventPublisher events;

    boolean paymentIntent(StripeEvent event) {
        var o = event.object();
        var id = o.id();
        var intent = id == null ? null : escrows.intentByStripeId(id).orElse(null);
        if (intent == null) {
            return false; // not a Northline PaymentIntent (or not recorded yet: hold() reads Stripe itself)
        }
        var target = switch (event.type()) {
            case "payment_intent.amount_capturable_updated" -> IntentStatus.AUTHORIZED;
            case "payment_intent.succeeded" -> IntentStatus.CAPTURED;
            case "payment_intent.canceled" -> IntentStatus.CANCELED;
            default -> IntentStatus.FAILED;
        };
        if (!advances(intent.state(), target)) {
            return true;
        }
        switch (target) {
            case AUTHORIZED -> escrows.markAuthorized(intent.id(), event.created());
            case CAPTURED -> escrows.recordCapture(intent.id(), o.text("latest_charge"));
            default -> escrows.markPaymentIntent(intent.id(), target);
        }
        if (target == IntentStatus.CANCELED) {
            // a hold that lapsed or was canceled at Stripe while the job still needs it: the customer must pay again
            escrows.findByPaymentIntentId(intent.id())
                    .filter(e -> e.awaitingCapture())
                    .ifPresent(escrow -> {
                        log.warn("Escrow {}: its card hold {} was canceled at Stripe", escrow.getId(), id);
                        events.publishEvent(new PaymentReauthorizationRequired(
                                Ids.next(),
                                event.created(),
                                escrow.getId(),
                                escrow.getMerchantId(),
                                Objects.requireNonNullElse(escrow.getCustomerId(), ""),
                                escrow.getRefType(),
                                escrow.getRefId(),
                                event.created()));
                    });
        }
        return true;
    }

    /** Stripe statuses only move forward; a failed attempt may still be followed by an authorization. */
    static boolean advances(IntentStatus from, IntentStatus to) {
        return rank(to) > rank(from) || (from == IntentStatus.REQUIRES_ACTION && to == IntentStatus.FAILED);
    }

    private static int rank(IntentStatus status) {
        return switch (status) {
            case REQUIRES_ACTION, FAILED -> 0;
            case AUTHORIZED -> 1;
            case CAPTURED, CANCELED -> 2;
            case REFUNDED -> 3;
        };
    }

    boolean chargeRefunded(StripeObject charge) {
        var pi = charge.text("payment_intent");
        var intent = pi == null ? null : escrows.intentByStripeId(pi).orElse(null);
        if (intent == null) {
            return false;
        }
        if (charge.flag("refunded")) {
            escrows.markPaymentIntent(intent.id(), IntentStatus.REFUNDED);
        }
        return true;
    }

    boolean refund(StripeObject refund) {
        var id = refund.id();
        var status = refund.text("status");
        if (id == null || status == null) {
            return false;
        }
        if (!cases.refundStripeStatus(id, status)) {
            log.warn("Stripe refund {} ({}) wasn't made by Northline", id, status);
            return false;
        }
        if ("failed".equals(status) || "canceled".equals(status)) {
            log.error(
                    "Stripe refund {} {}: {} — the customer hasn't been paid back",
                    id,
                    status,
                    refund.text("failure_reason"));
        }
        return true;
    }

    boolean transferReversed(StripeObject transfer) {
        var id = transfer.id();
        var reversed = transfer.number("amount_reversed");
        return id != null && reversed != null && escrows.syncReversed(id, reversed);
    }
}
