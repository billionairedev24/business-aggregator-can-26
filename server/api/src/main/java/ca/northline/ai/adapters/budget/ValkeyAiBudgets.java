package ca.northline.ai.adapters.budget;

import ca.northline.ai.api.AiCompletions.Caller;
import ca.northline.ai.api.AiUnavailable;
import ca.northline.ai.application.AiBudgets;
import ca.northline.ai.application.AiProperties;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Budgets in Valkey, shared by every api instance: {@code nl:ai:{p:<person>}:rpm:<minute>} (INCR, 2 min TTL) and
 * {@code nl:ai:{p:<person>}:tok:<day>} / {@code nl:ai:{m:<merchant>}:tok:<day>} (INCRBY, 2 day TTL). Valkey down means
 * AI is unavailable (503), never unmetered.
 */
@Slf4j
@RequiredArgsConstructor
public final class ValkeyAiBudgets implements AiBudgets {

    static final String PREFIX = "nl:ai:";
    private static final Duration DAY_TTL = Duration.ofDays(2);
    private static final Duration MINUTE_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redis;
    private final AiProperties.Budget limits;
    private final Clock clock;

    @Override
    public void admit(Caller caller) {
        try {
            var rate = PREFIX + "{p:" + caller.personId() + "}:rpm:" + BudgetWindows.minute(clock);
            var count = redis.opsForValue().increment(rate);
            if (count != null && count == 1) {
                redis.expire(rate, MINUTE_TTL);
            }
            if (count != null && count > limits.personRequestsPerMinute()) {
                throw BudgetWindows.tooFast(clock);
            }
            if (spent(personKey(caller)) >= limits.personTokensPerDay()) {
                throw BudgetWindows.personSpent(clock);
            }
            var merchant = merchantKey(caller);
            if (merchant != null && spent(merchant) >= limits.merchantTokensPerDay()) {
                throw BudgetWindows.merchantSpent(clock);
            }
        } catch (DataAccessException e) {
            log.error("AI budgets unavailable (Valkey): {}", e.getMessage());
            throw new AiUnavailable("AI is unavailable right now.", e);
        }
    }

    @Override
    public void charge(Caller caller, long tokens) {
        if (tokens <= 0) {
            return;
        }
        try {
            add(personKey(caller), tokens);
            var merchant = merchantKey(caller);
            if (merchant != null) {
                add(merchant, tokens);
            }
        } catch (DataAccessException e) {
            log.error("AI tokens not charged (Valkey): {}", e.getMessage());
        }
    }

    private void add(String key, long tokens) {
        var total = redis.opsForValue().increment(key, tokens);
        if (total != null && total == tokens) {
            redis.expire(key, DAY_TTL);
        }
    }

    private long spent(String key) {
        var v = redis.opsForValue().get(key);
        return v == null ? 0 : Long.parseLong(v);
    }

    private String personKey(Caller caller) {
        return PREFIX + "{p:" + caller.personId() + "}:tok:" + BudgetWindows.day(clock);
    }

    private @Nullable String merchantKey(Caller caller) {
        return caller.merchantId() == null
                ? null
                : PREFIX + "{m:" + caller.merchantId() + "}:tok:" + BudgetWindows.day(clock);
    }
}
