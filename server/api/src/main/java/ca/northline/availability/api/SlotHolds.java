package ca.northline.availability.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Slot holds during checkout (S-55; CLAUDE.md: "slot holds (10 min TTL)" in Valkey/Redis): picking a slot and going to
 * payment reserves it for one bookable member so nobody else can take it while the customer pays. Other customers'
 * holds take the slot in {@link ProviderSlots}; the customer's own don't. One hold per customer and business: a new
 * one replaces the last.
 */
public interface SlotHolds {

    Duration TTL = Duration.ofMinutes(10);

    /**
     * @param bookingId the booking this hold becomes once paid (chosen now: the PaymentIntent refers to it)
     */
    record Hold(
            String id,
            String bookingId,
            String merchantId,
            String memberUserId,
            String customerId,
            @Nullable String serviceId,
            Instant startsAt,
            Instant endsAt,
            Instant expiresAt) {}

    /** @throws ca.northline.shared.Conflict {@code slot_taken} when no member is free for it any more */
    Hold hold(String merchantId, @Nullable String serviceId, Instant startsAt, int durationMin, String customerId);

    /** An unexpired hold. */
    Optional<Hold> find(String holdId);

    void release(String holdId);

    /** Keeps what the customer entered for checkout with the hold (expires with it). */
    void attach(String holdId, String checkout);

    Optional<String> checkout(String holdId);
}
