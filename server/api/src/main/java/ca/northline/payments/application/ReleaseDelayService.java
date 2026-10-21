package ca.northline.payments.application;

import ca.northline.payments.api.CompletionPhotoRule;
import ca.northline.payments.api.EscrowReleaseDelayed;
import ca.northline.payments.api.ReleaseDelays;
import ca.northline.shared.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-156: the S-93 rule "completion photo missing → escrow release delayed 48 h". When a job is completed without a
 * photo, its escrow (and a deposit quote's balance) is released {@link CompletionPhotoRule#missingPhotoDelay()} later
 * than the services clock — and the customer's sign-off doesn't release it before that either. Staff can lift it (the
 * standing module). Idempotent per escrow.
 */
@Service
@RequiredArgsConstructor
@Transactional
class ReleaseDelayService implements ReleaseDelays {

    private final ReleaseDelayStore store;
    private final EscrowRepository escrows;
    private final CompletionPhotoRule rule;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final Clock clock;

    /** After the release clock started for a completed job without photos. */
    void completedWithoutPhotos(String bookingId, Instant completedAt) {
        var delay = rule.missingPhotoDelay();
        if (delay.isZero() || delay.isNegative()) {
            return;
        }
        var hours = (int) Math.max(1, delay.toHours());
        var delayed = new ArrayList<String>();
        Instant until = null;
        String merchantId = null;
        for (var refType : List.of(EscrowFulfilmentListener.BOOKING, EscrowFulfilmentListener.BALANCE)) {
            var escrow = escrows.findByRefForUpdate(refType, bookingId).orElse(null);
            if (escrow == null || escrow.getReleaseAt() == null) {
                continue;
            }
            var at = escrow.getReleaseAt().plus(Duration.ofHours(hours));
            if (!store.insert(escrow.getId(), escrow.getMerchantId(), bookingId, hours, at, clock.instant())) {
                continue;
            }
            if (escrow.holdReleaseUntil(at)) {
                escrows.update(escrow);
            }
            delayed.add(escrow.getId());
            until = until == null || at.isAfter(until) ? at : until;
            merchantId = escrow.getMerchantId();
        }
        if (!delayed.isEmpty() && until != null && merchantId != null) {
            events.publishEvent(
                    new EscrowReleaseDelayed(Ids.next(), completedAt, bookingId, merchantId, delayed, hours, until));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Delay> of(String merchantId, int limit) {
        return store.of(merchantId, limit);
    }

    @Override
    public boolean lift(String bookingId, String staffId) {
        var active = store.active(bookingId);
        var now = clock.instant();
        for (var delay : active) {
            store.lifted(delay.escrowId(), staffId, now);
            escrows.findById(delay.escrowId()).ifPresent(escrow -> {
                var normal = delay.delayedUntil().minus(Duration.ofHours(delay.hours()));
                escrow.releaseNoLaterThan(normal.isBefore(now) ? now : normal);
                escrows.update(escrow);
            });
        }
        return !active.isEmpty();
    }
}
