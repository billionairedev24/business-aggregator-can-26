package ca.northline.availability.persistence;

import ca.northline.availability.api.SlotHolds.Hold;
import ca.northline.availability.application.SlotHoldStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slot holds in Valkey / Redis (CLAUDE.md: slot holds, 10 min TTL) — every api replica sees the same holds.
 *
 * <ul>
 *   <li>{@code nl:slothold:{m:<merchant>}:member:<member>} — sorted set of the member's hold ids, score = expiry (ms);
 *   <li>{@code nl:slothold:{m:<merchant>}:h:<id>} — the hold (start, end in ms; JSON), expiring with it;
 *   <li>{@code nl:slothold:id:<id>} → the hold's key, {@code nl:slothold:c:<customer>:<merchant>} → the customer's
 *       holds with that business, {@code nl:slothold:checkout:<id>} → what the customer entered at checkout.
 * </ul>
 *
 * Placing is one Lua script: drop the member's expired holds, refuse when an unexpired one overlaps the new hold widened
 * by the buffer, else write it. The member's keys share a hash tag, so the script touches one slot.
 */
@Repository
@Profile("!local & !test")
class RedisSlotHoldStore implements SlotHoldStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final RedisScript<Long> PLACE = RedisScript.of("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            local from = tonumber(ARGV[3]) - tonumber(ARGV[5])
            local to = tonumber(ARGV[4]) + tonumber(ARGV[5])
            for _, id in ipairs(redis.call('ZRANGE', KEYS[1], 0, -1)) do
              local h = redis.call('HMGET', ARGV[9] .. id, 'start', 'end')
              if h[1] and tonumber(h[1]) < to and tonumber(h[2]) > from then return 0 end
            end
            redis.call('HSET', KEYS[2], 'start', ARGV[3], 'end', ARGV[4], 'json', ARGV[7])
            redis.call('PEXPIRE', KEYS[2], ARGV[8])
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[6])
            redis.call('PEXPIRE', KEYS[1], ARGV[8])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    RedisSlotHoldStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String prefix(String merchantId) {
        return "nl:slothold:{m:" + merchantId + "}:";
    }

    static String memberKey(String merchantId, String memberUserId) {
        return prefix(merchantId) + "member:" + memberUserId;
    }

    static String customerKey(String customerId, String merchantId) {
        return "nl:slothold:c:" + customerId + ":" + merchantId;
    }

    @Override
    public boolean place(Hold hold, Duration buffer, Instant now) {
        var ttl = Math.max(1, Duration.between(now, hold.expiresAt()).toMillis());
        var holdKey = prefix(hold.merchantId()) + "h:" + hold.id();
        var placed = redis.execute(
                PLACE,
                List.of(memberKey(hold.merchantId(), hold.memberUserId()), holdKey),
                String.valueOf(now.toEpochMilli()),
                String.valueOf(hold.expiresAt().toEpochMilli()),
                String.valueOf(hold.startsAt().toEpochMilli()),
                String.valueOf(hold.endsAt().toEpochMilli()),
                String.valueOf(buffer.toMillis()),
                hold.id(),
                JSON.writeValueAsString(hold),
                String.valueOf(ttl),
                prefix(hold.merchantId()) + "h:");
        if (!Long.valueOf(1).equals(placed)) {
            return false;
        }
        var ttlDuration = Duration.ofMillis(ttl);
        redis.opsForValue().set("nl:slothold:id:" + hold.id(), holdKey, ttlDuration);
        var customer = customerKey(hold.customerId(), hold.merchantId());
        redis.opsForSet().add(customer, hold.id());
        redis.expire(customer, ttlDuration);
        return true;
    }

    @Override
    public Optional<Hold> find(String holdId, Instant now) {
        var key = redis.opsForValue().get("nl:slothold:id:" + holdId);
        return key == null ? Optional.empty() : read(key, now);
    }

    @Override
    public List<Hold> ofMember(String merchantId, String memberUserId, Instant from, Instant to, Instant now) {
        var ids = redis.opsForZSet()
                .rangeByScore(
                        memberKey(merchantId, memberUserId),
                        (double) (now.toEpochMilli() + 1), // epoch millis are exact as a double (< 2^53)
                        Double.MAX_VALUE);
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
                .map(id -> read(prefix(merchantId) + "h:" + id, now))
                .flatMap(Optional::stream)
                .filter(h -> h.startsAt().isBefore(to) && h.endsAt().isAfter(from))
                .toList();
    }

    @Override
    public List<Hold> ofCustomer(String customerId, String merchantId, Instant now) {
        var ids = redis.opsForSet().members(customerKey(customerId, merchantId));
        if (ids == null) {
            return List.of();
        }
        return ids.stream().map(id -> find(id, now)).flatMap(Optional::stream).toList();
    }

    @Override
    public void remove(String holdId) {
        var key = redis.opsForValue().get("nl:slothold:id:" + holdId);
        if (key != null) {
            read(key, Instant.EPOCH).ifPresent(h -> {
                redis.opsForZSet().remove(memberKey(h.merchantId(), h.memberUserId()), holdId);
                redis.opsForSet().remove(customerKey(h.customerId(), h.merchantId()), holdId);
            });
            redis.delete(key);
        }
        redis.delete(List.of("nl:slothold:id:" + holdId, "nl:slothold:checkout:" + holdId));
    }

    @Override
    public void attach(String holdId, String checkout) {
        var key = redis.opsForValue().get("nl:slothold:id:" + holdId);
        var ttl = key == null ? null : redis.getExpire(key);
        if (ttl != null && ttl > 0) {
            redis.opsForValue().set("nl:slothold:checkout:" + holdId, checkout, Duration.ofSeconds(ttl));
        }
    }

    @Override
    public Optional<String> checkout(String holdId) {
        return Optional.ofNullable(redis.opsForValue().get("nl:slothold:checkout:" + holdId));
    }

    private Optional<Hold> read(String key, Instant now) {
        var json = (String) redis.opsForHash().get(key, "json");
        if (json == null) {
            return Optional.empty();
        }
        var hold = JSON.readValue(json, Hold.class);
        return hold.expiresAt().isAfter(now) || Objects.equals(now, Instant.EPOCH)
                ? Optional.of(hold)
                : Optional.empty();
    }
}
