package ca.northline.catalogue.application;

import ca.northline.catalogue.api.InstantBook;
import ca.northline.catalogue.api.InstantBookChanged;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link InstantBook} and the Studio's switch (S-156). The business may always turn instant book off; turning it on
 * while Northline has paused it answers 409 {@code instant_book_paused} (the switch respects the pause). Every change of
 * the effective value publishes {@link InstantBookChanged} in the same transaction.
 */
@Service
@RequiredArgsConstructor
@Transactional
class InstantBookService implements InstantBook, SwitchInstantBook {

    private final InstantBookStore store;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public State state(String merchantId) {
        return store.find(merchantId).orElse(State.DEFAULT);
    }

    @Override
    public State set(String merchantId, boolean enabled, String userId) {
        var current = store.lock(merchantId).orElse(State.DEFAULT);
        if (enabled && current.pausedAt() != null) {
            throw new Conflict("instant_book_paused", PAUSED);
        }
        var next =
                new State(enabled, current.pausedAt(), current.pausedCause(), current.pausedReason(), clock.instant());
        save(merchantId, current, next, userId);
        return next;
    }

    @Override
    public boolean pause(String merchantId, String cause, String reason, String actorId) {
        var current = store.lock(merchantId).orElse(State.DEFAULT);
        if (current.pausedAt() != null) {
            return false;
        }
        var now = clock.instant();
        save(merchantId, current, new State(current.enabled(), now, cause, reason, now), actorId);
        return true;
    }

    @Override
    public boolean resume(String merchantId, @Nullable String cause, String actorId) {
        var current = store.lock(merchantId).orElse(State.DEFAULT);
        if (current.pausedAt() == null || (cause != null && !cause.equals(current.pausedCause()))) {
            return false;
        }
        save(merchantId, current, new State(current.enabled(), null, null, null, clock.instant()), actorId);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> pausedFor(String cause) {
        return store.pausedFor(cause);
    }

    private void save(String merchantId, State before, State after, String actorId) {
        var now = clock.instant();
        store.save(
                merchantId, after.enabled(), after.pausedAt(), after.pausedCause(), after.pausedReason(), actorId, now);
        if (before.effective() != after.effective()) {
            events.publishEvent(new InstantBookChanged(Ids.next(), now, merchantId, merchantId, after.effective()));
        }
    }
}
