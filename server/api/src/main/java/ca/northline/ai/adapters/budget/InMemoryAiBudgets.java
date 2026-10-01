package ca.northline.ai.adapters.budget;

import ca.northline.ai.api.AiCompletions.Caller;
import ca.northline.ai.application.AiBudgets;
import ca.northline.ai.application.AiProperties;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;

/** The same budgets in this JVM only (local runs without Valkey, tests). Refused under staging/prod. */
@RequiredArgsConstructor
public final class InMemoryAiBudgets implements AiBudgets {

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final AiProperties.Budget limits;
    private final Clock clock;

    @Override
    public void admit(Caller caller) {
        sweep();
        if (caller.isSystem()) {
            if (counter(person(caller)).get() >= limits.systemTokensPerDay()) {
                throw BudgetWindows.personSpent(clock);
            }
            return;
        }
        var rate = counter("p:" + caller.personId() + ":rpm:" + BudgetWindows.minute(clock));
        if (rate.incrementAndGet() > limits.personRequestsPerMinute()) {
            throw BudgetWindows.tooFast(clock);
        }
        if (counter(person(caller)).get() >= limits.personTokensPerDay()) {
            throw BudgetWindows.personSpent(clock);
        }
        if (caller.merchantId() != null && counter(merchant(caller)).get() >= limits.merchantTokensPerDay()) {
            throw BudgetWindows.merchantSpent(clock);
        }
    }

    @Override
    public void charge(Caller caller, long tokens) {
        counter(person(caller)).addAndGet(tokens);
        if (caller.merchantId() != null) {
            counter(merchant(caller)).addAndGet(tokens);
        }
    }

    private AtomicLong counter(String key) {
        return counters.computeIfAbsent(key, _ -> new AtomicLong());
    }

    private String person(Caller caller) {
        return "p:" + caller.personId() + ":tok:" + BudgetWindows.day(clock);
    }

    private String merchant(Caller caller) {
        return "m:" + caller.merchantId() + ":tok:" + BudgetWindows.day(clock);
    }

    private void sweep() {
        if (counters.size() > 10_000) {
            var minute = ":rpm:" + BudgetWindows.minute(clock);
            var day = ":tok:" + BudgetWindows.day(clock);
            counters.keySet().removeIf(k -> !k.endsWith(minute) && !k.endsWith(day));
        }
    }
}
