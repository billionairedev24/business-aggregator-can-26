package ca.northline.availability.persistence;

import ca.northline.availability.api.SlotHolds.Hold;
import ca.northline.availability.application.SlotHoldStore;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * Slot holds in memory under {@code local} and {@code test} (no Valkey there; one api instance). The same rules as
 * {@link RedisSlotHoldStore}: placing checks the member's other unexpired holds under one lock.
 */
@Repository
@Profile({"local", "test"})
class InMemorySlotHoldStore implements SlotHoldStore {

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Hold> holds = new HashMap<>();
    private final Map<String, String> checkouts = new HashMap<>();

    @Override
    public boolean place(Hold hold, Duration buffer, Instant now) {
        lock.lock();
        try {
            purge(now);
            var from = hold.startsAt().minus(buffer);
            var to = hold.endsAt().plus(buffer);
            var clash = holds.values().stream()
                    .anyMatch(h -> h.merchantId().equals(hold.merchantId())
                            && h.memberUserId().equals(hold.memberUserId())
                            && h.startsAt().isBefore(to)
                            && h.endsAt().isAfter(from));
            if (clash) {
                return false;
            }
            holds.put(hold.id(), hold);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Hold> find(String holdId, Instant now) {
        return read(now, () -> Optional.ofNullable(holds.get(holdId)));
    }

    @Override
    public List<Hold> ofMember(String merchantId, String memberUserId, Instant from, Instant to, Instant now) {
        return read(
                now,
                () -> holds.values().stream()
                        .filter(h -> h.merchantId().equals(merchantId)
                                && h.memberUserId().equals(memberUserId))
                        .filter(h -> h.startsAt().isBefore(to) && h.endsAt().isAfter(from))
                        .toList());
    }

    @Override
    public List<Hold> ofCustomer(String customerId, String merchantId, Instant now) {
        return read(
                now,
                () -> holds.values().stream()
                        .filter(h -> h.customerId().equals(customerId)
                                && h.merchantId().equals(merchantId))
                        .toList());
    }

    @Override
    public void remove(String holdId) {
        lock.lock();
        try {
            holds.remove(holdId);
            checkouts.remove(holdId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void attach(String holdId, String checkout) {
        lock.lock();
        try {
            if (holds.containsKey(holdId)) {
                checkouts.put(holdId, checkout);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<String> checkout(String holdId) {
        lock.lock();
        try {
            return Optional.ofNullable(checkouts.get(holdId));
        } finally {
            lock.unlock();
        }
    }

    private <T> T read(Instant now, java.util.function.Supplier<T> query) {
        lock.lock();
        try {
            purge(now);
            return query.get();
        } finally {
            lock.unlock();
        }
    }

    private void purge(Instant now) {
        holds.values().removeIf(h -> !h.expiresAt().isAfter(now));
        checkouts.keySet().retainAll(holds.keySet());
    }
}
