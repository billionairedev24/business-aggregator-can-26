package ca.northline.auth.ratelimit;

import ca.northline.auth.application.LimitScope;
import ca.northline.auth.application.RateLimiter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Valkey/Redis counters: {@code nl:auth-rl:{<action>}:<scope>:<hash>:events|lock|strikes}. The hash tag keeps one
 * call's keys in one slot. When Valkey is unreachable, {@link #check} and {@link #record} throw
 * {@link RateLimiter.Unavailable} and {@code AttemptLimits} decides (S-20: fail open or closed); a failed reset is only
 * logged (it can only make the limits stricter).
 */
@Slf4j
final class RedisRateLimiter implements RateLimiter {

    static final String PREFIX = "nl:auth-rl:";

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("ratelimit/attempt.lua"), List.class);

    private final StringRedisTemplate redis;

    RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Decision check(List<Limit> limits) {
        return run("check", limits);
    }

    @Override
    public Decision record(List<Limit> limits) {
        return run("record", limits);
    }

    @Override
    public void reset(List<Limit> limits) {
        var keys = new ArrayList<String>();
        for (var limit : limits) {
            keys.add(key(limit, "events"));
            keys.add(key(limit, "strikes"));
        }
        try {
            redis.delete(keys);
        } catch (DataAccessException e) {
            log.error("Rate limits unavailable (Valkey): reset skipped — {}", e.getMessage());
        }
    }

    private Decision run(String mode, List<Limit> limits) {
        var keys = new ArrayList<String>(limits.size() * 3);
        var args = new ArrayList<String>(2 + limits.size() * 5);
        args.add(mode);
        args.add(UUID.randomUUID().toString());
        for (var limit : limits) {
            keys.add(key(limit, "events"));
            keys.add(key(limit, "lock"));
            keys.add(key(limit, "strikes"));
            args.add(Long.toString(limit.rule().window().toMillis()));
            args.add(Integer.toString(limit.threshold()));
            args.add(Long.toString(limit.rule().lockout().toMillis()));
            args.add(Long.toString(limit.rule().maxLockout().toMillis()));
            args.add(Long.toString(limit.backoffMemory().toMillis()));
        }
        List<?> result;
        try {
            result = redis.execute(SCRIPT, keys, args.toArray());
        } catch (DataAccessException e) {
            throw new RateLimiter.Unavailable("Valkey: " + e.getMessage(), e);
        }
        if (result == null || result.size() < 3 || number(result.get(0)) == 1) {
            return Decision.ALLOWED;
        }
        var newly = EnumSet.noneOf(LimitScope.class);
        var mask = number(result.get(2));
        for (int i = 0; i < limits.size(); i++) {
            if ((mask & (1L << i)) != 0) {
                newly.add(limits.get(i).scope());
            }
        }
        return Decision.denied(Duration.ofMillis(number(result.get(1))), newly);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    static String key(Limit limit, String part) {
        return PREFIX + "{" + limit.action().code() + "}:" + limit.scope().code() + ":" + limit.subject() + ":" + part;
    }
}
