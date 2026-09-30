package ca.northline.availability.application;

import ca.northline.availability.api.SlotHolds;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@link SlotHolds}: the slot must be free (the same rules the customer's calendar shows) for some bookable member,
 * then that member's time is reserved atomically in the store — the travel buffer included, as for jobs. A customer
 * holds at most one slot per business.
 */
@Service
@RequiredArgsConstructor
class SlotHoldService implements SlotHolds {

    static final String TAKEN = "That time was just taken. Pick another slot.";

    private final CustomerSlotService slots;
    private final SlotHoldStore store;
    private final Clock clock;

    @Override
    public Hold hold(String merchantId, @Nullable String serviceId, Instant startsAt, int durationMin, String customerId) {
        var now = clock.instant();
        store.ofCustomer(customerId, merchantId, now).forEach(h -> store.remove(h.id()));
        var buffer = Duration.ofMinutes(slots.rules(merchantId).bufferMin());
        var endsAt = startsAt.plus(Duration.ofMinutes(durationMin));
        for (var member : slots.freeMembers(merchantId, startsAt, durationMin, customerId)) {
            var hold = new Hold(
                    Ids.next(), Ids.next(), merchantId, member, customerId, serviceId, startsAt, endsAt, now.plus(TTL));
            if (store.place(hold, buffer, now)) {
                return hold;
            }
        }
        throw new Conflict("slot_taken", TAKEN);
    }

    @Override
    public Optional<Hold> find(String holdId) {
        return store.find(holdId, clock.instant());
    }

    @Override
    public void release(String holdId) {
        store.remove(holdId);
    }

    @Override
    public void attach(String holdId, String checkout) {
        store.attach(holdId, checkout);
    }

    @Override
    public Optional<String> checkout(String holdId) {
        return store.checkout(holdId);
    }
}
