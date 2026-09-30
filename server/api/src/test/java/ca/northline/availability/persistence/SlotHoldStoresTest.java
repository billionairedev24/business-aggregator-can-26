package ca.northline.availability.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.availability.api.SlotHolds.Hold;
import ca.northline.availability.application.SlotHoldStore;
import ca.northline.shared.Ids;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * S-55 slot holds: the Valkey store (a real Valkey 8 in a container, the Lua script) and the in-memory one used under
 * local/test behave the same — overlaps with the travel buffer refused, expiry, the race for one member's time.
 */
class SlotHoldStoresTest {

    static final GenericContainer<?> VALKEY =
            new GenericContainer<>(DockerImageName.parse("valkey/valkey:8-alpine")).withExposedPorts(6379);

    static LettuceConnectionFactory connections;

    static final Instant NOW = Instant.parse("2026-09-30T18:00:00Z");
    static final Instant NINE = Instant.parse("2026-10-01T15:00:00Z");

    @BeforeAll
    static void start() {
        VALKEY.start();
        connections = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(VALKEY.getHost(), VALKEY.getMappedPort(6379)));
        connections.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        connections.destroy();
        VALKEY.stop();
    }

    static Stream<SlotHoldStore> stores() {
        var redis = new StringRedisTemplate(connections);
        return Stream.of(new InMemorySlotHoldStore(), new RedisSlotHoldStore(redis));
    }

    static Hold hold(String merchant, String member, String customer, Instant start, int minutes, Instant expires) {
        return new Hold(
                Ids.next(),
                Ids.next(),
                merchant,
                member,
                customer,
                "svc",
                start,
                start.plus(Duration.ofMinutes(minutes)),
                expires);
    }

    @ParameterizedTest
    @MethodSource("stores")
    void refusesOverlapsIncludingTheBuffer_andForgetsExpiredHolds(SlotHoldStore store) {
        var merchant = Ids.next();
        var member = Ids.next();
        var expires = NOW.plus(Duration.ofMinutes(10));
        var first = hold(merchant, member, "c1", NINE, 60, expires);
        assertThat(store.place(first, Duration.ofMinutes(20), NOW)).isTrue();
        // 10:15 starts inside the 20-minute buffer after 9–10
        assertThat(store.place(
                        hold(merchant, member, "c2", NINE.plus(Duration.ofMinutes(75)), 60, expires),
                        Duration.ofMinutes(20),
                        NOW))
                .isFalse();
        assertThat(store.place(
                        hold(merchant, member, "c2", NINE.plus(Duration.ofMinutes(80)), 60, expires),
                        Duration.ofMinutes(20),
                        NOW))
                .isTrue();
        // another member of the business isn't affected
        assertThat(store.place(hold(merchant, Ids.next(), "c3", NINE, 60, expires), Duration.ofMinutes(20), NOW))
                .isTrue();
        assertThat(store.find(first.id(), NOW)).contains(first);
        assertThat(store.ofMember(merchant, member, NINE, NINE.plus(Duration.ofHours(1)), NOW))
                .containsExactly(first);
        assertThat(store.ofCustomer("c1", merchant, NOW)).containsExactly(first);
        store.attach(first.id(), "sealed-checkout");
        assertThat(store.checkout(first.id())).contains("sealed-checkout");
        // ten minutes later the hold is gone and the time is free again
        var later = NOW.plus(Duration.ofMinutes(11));
        assertThat(store.find(first.id(), later)).isEmpty();
        assertThat(store.place(
                        hold(merchant, member, "c4", NINE, 60, later.plus(Duration.ofMinutes(10))),
                        Duration.ofMinutes(20),
                        later))
                .isTrue();
        store.remove(first.id());
        assertThat(store.checkout(first.id())).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("stores")
    void ofTwentyCustomersRacingForOneMembersSlot_oneWins(SlotHoldStore store) throws Exception {
        var merchant = Ids.next();
        var member = Ids.next();
        var start = new CountDownLatch(1);
        var tasks = new ArrayList<Callable<Boolean>>();
        for (int i = 0; i < 20; i++) {
            var h = hold(merchant, member, "c" + i, NINE, 60, NOW.plus(Duration.ofMinutes(10)));
            tasks.add(() -> {
                start.await();
                return store.place(h, Duration.ZERO, NOW);
            });
        }
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            List<Boolean> won = new ArrayList<>();
            for (var f : futures) {
                won.add(f.get());
            }
            assertThat(won.stream().filter(Boolean::booleanValue)).hasSize(1);
        }
        assertThat(store.ofMember(merchant, member, NINE, NINE.plus(Duration.ofHours(1)), NOW))
                .hasSize(1);
    }
}
