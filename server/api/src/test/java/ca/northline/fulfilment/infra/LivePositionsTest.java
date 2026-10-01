package ca.northline.fulfilment.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.fulfilment.api.CourierLocations.Position;
import ca.northline.shared.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * S-88 on a real Valkey 8, two adapter instances standing in for two api replicas: the ping rate limit holds across
 * replicas, the latest position (only the latest — no history key) is readable from the other replica and expires,
 * and a "moved" crosses replicas to that order's subscribers only. The in-memory adapter behaves the same.
 */
class LivePositionsTest {

    static final GenericContainer<?> VALKEY =
            new GenericContainer<>(DockerImageName.parse("valkey/valkey:8-alpine")).withExposedPorts(6379);

    static LettuceConnectionFactory connections;

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

    @Test
    void positionsAndMovesCrossReplicasAndNothingButTheLatestIsKept() {
        var redis = new StringRedisTemplate(connections);
        var a = new RedisLivePositions(redis, connections);
        var b = new RedisLivePositions(redis, connections);
        try {
            var courier = Ids.next();
            assertThat(a.allow(courier, Duration.ofSeconds(2))).isTrue();
            assertThat(b.allow(courier, Duration.ofSeconds(2)))
                    .as("the other replica sees the limit")
                    .isFalse();
            Awaitility.await()
                    .atMost(Duration.ofSeconds(4))
                    .pollInterval(Duration.ofMillis(200))
                    .until(() -> b.allow(courier, Duration.ofSeconds(2)));

            var at = Instant.parse("2026-10-01T23:00:00Z");
            a.put(courier, new Position(51.05, -114.07, 90.0, at), Duration.ofSeconds(60));
            a.put(courier, new Position(51.06, -114.08, null, at.plusSeconds(4)), Duration.ofSeconds(60));
            assertThat(b.latest(courier)).contains(new Position(51.06, -114.08, null, at.plusSeconds(4)));
            assertThat(redis.keys("nl:courier-pos:" + courier + "*")).hasSize(1);
            assertThat(redis.getExpire("nl:courier-pos:" + courier)).isBetween(1L, 60L);
            a.put(courier, new Position(1, 1, null, at), Duration.ofMillis(300));
            Awaitility.await()
                    .atMost(Duration.ofSeconds(3))
                    .until(() -> b.latest(courier).isEmpty());

            var order = Ids.next();
            var mine = new AtomicInteger();
            var others = new AtomicInteger();
            var subscription = b.subscribe(order, mine::incrementAndGet);
            b.subscribe(Ids.next(), others::incrementAndGet);
            Awaitility.await() // the pattern subscription is asynchronous: publish until the listener is up
                    .atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(100))
                    .until(() -> {
                        a.moved(order);
                        return mine.get() > 0;
                    });
            // let the publishes of the warm-up loop arrive, then count one move
            Awaitility.await().pollDelay(Duration.ofMillis(300)).until(() -> true);
            mine.set(0);
            a.moved(order);
            Awaitility.await().atMost(Duration.ofSeconds(1)).until(() -> mine.get() >= 1);
            assertThat(others.get()).isZero();
            subscription.close();
            var before = mine.get();
            a.moved(order);
            Awaitility.await().during(Duration.ofMillis(300)).until(() -> mine.get() == before);
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    void inMemoryFollowsTheApplicationClock() {
        var now = Instant.parse("2026-10-01T23:00:00Z");
        var clock = new Clock() {
            Instant at = now;

            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return at;
            }
        };
        var live = new MemoryLivePositions(clock);
        assertThat(live.allow("c", Duration.ofSeconds(2))).isTrue();
        assertThat(live.allow("c", Duration.ofSeconds(2))).isFalse();
        clock.at = now.plusSeconds(2);
        assertThat(live.allow("c", Duration.ofSeconds(2))).isTrue();
        live.put("c", new Position(1, 2, null, now), Duration.ofMinutes(5));
        assertThat(live.latest("c")).isPresent();
        clock.at = now.plus(Duration.ofMinutes(6));
        assertThat(live.latest("c")).isEmpty();
        var moves = new AtomicInteger();
        var sub = live.subscribe("o", moves::incrementAndGet);
        live.moved("o");
        live.moved("other");
        sub.close();
        live.moved("o");
        assertThat(moves.get()).isEqualTo(1);
    }
}
