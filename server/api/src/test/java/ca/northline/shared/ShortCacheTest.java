package ca.northline.shared;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.support.MovableClock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Engineering follow-ups (S-119 F5): the landing pages' short cache. */
class ShortCacheTest {

    final MovableClock clock = new MovableClock();
    final AtomicInteger loads = new AtomicInteger();

    String load() {
        return "v" + loads.incrementAndGet();
    }

    @Test
    void keepsAValueForItsTtl_perKey() {
        var cache = new ShortCache<String, String>(clock, Duration.ofSeconds(30), 10);
        assertThat(cache.get("calgary", this::load)).isEqualTo("v1");
        clock.advance(Duration.ofSeconds(29));
        assertThat(cache.get("calgary", this::load)).isEqualTo("v1");
        assertThat(cache.get("halifax", this::load)).isEqualTo("v2");
        clock.advance(Duration.ofSeconds(2));
        assertThat(cache.get("calgary", this::load)).isEqualTo("v3");
    }

    @Test
    void invalidateAll_dropsEverything() {
        var cache = new ShortCache<String, String>(clock, Duration.ofSeconds(30), 10);
        cache.get("a", this::load);
        cache.get("b", this::load);
        cache.invalidateAll();
        assertThat(cache.size()).isZero();
        assertThat(cache.get("a", this::load)).isEqualTo("v3");
    }

    @Test
    void aLoadThatStartedBeforeAnInvalidation_isReturnedButNotKept() {
        var cache = new ShortCache<String, String>(clock, Duration.ofSeconds(30), 10);
        var stale = cache.get("a", () -> {
            cache.invalidateAll(); // a business was approved while this read ran
            return "before the change";
        });
        assertThat(stale).isEqualTo("before the change");
        assertThat(cache.get("a", this::load)).isEqualTo("v1");
    }

    @Test
    void zeroTtlTurnsItOff_andItNeverGrowsPastItsBound() {
        var off = new ShortCache<String, String>(clock, Duration.ZERO, 10);
        off.get("a", this::load);
        assertThat(off.get("a", this::load)).isEqualTo("v2");
        var small = new ShortCache<Integer, String>(clock, Duration.ofSeconds(30), 3);
        for (var i = 0; i < 10; i++) {
            small.get(i, this::load);
        }
        assertThat(small.size()).isLessThanOrEqualTo(3);
    }
}
