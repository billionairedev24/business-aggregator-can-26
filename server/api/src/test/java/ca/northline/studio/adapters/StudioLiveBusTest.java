package ca.northline.studio.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.Ids;
import ca.northline.studio.application.StudioLive.Signal;
import ca.northline.studio.application.StudioLive.Topic;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * S-68: the Valkey bus (a real Valkey 8) carries a signal from the replica where the change committed to the replica
 * that holds the browser's stream, within a second; only the business's own subscribers get it; a closed subscription
 * gets nothing more. The in-memory bus behaves the same inside one process.
 */
class StudioLiveBusTest {

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
    void aSignalCrossesReplicasWithinASecond() {
        var redis = new StringRedisTemplate(connections);
        var replicaA = new RedisStudioLive(redis, connections);
        var replicaB = new RedisStudioLive(redis, connections);
        try {
            var merchant = Ids.next();
            List<Signal> mine = new CopyOnWriteArrayList<>();
            List<Signal> others = new CopyOnWriteArrayList<>();
            var subscription = replicaB.subscribe(merchant, mine::add);
            replicaB.subscribe(Ids.next(), others::add);
            var thread = Ids.next();
            Awaitility.await() // the pattern subscription is asynchronous: publish until the listener is up
                    .atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(100))
                    .until(() -> {
                        replicaA.signal(merchant, new Signal(Topic.MESSAGE, thread));
                        return !mine.isEmpty();
                    });
            mine.clear();
            replicaA.signal(merchant, new Signal(Topic.KITCHEN, null));
            Awaitility.await()
                    .atMost(Duration.ofSeconds(1))
                    .untilAsserted(() -> assertThat(mine).contains(new Signal(Topic.KITCHEN, null)));
            assertThat(others).isEmpty();

            subscription.close();
            mine.clear();
            replicaA.signal(merchant, new Signal(Topic.ORDERS, Ids.next()));
            Awaitility.await().during(Duration.ofMillis(300)).until(mine::isEmpty);
        } finally {
            replicaA.close();
            replicaB.close();
        }
    }

    @Test
    void inMemoryDeliversToTheBusinessSubscribersOnly() {
        var bus = new MemoryStudioLive();
        var merchant = Ids.next();
        List<Signal> mine = new CopyOnWriteArrayList<>();
        List<Signal> others = new CopyOnWriteArrayList<>();
        var subscription = bus.subscribe(merchant, mine::add);
        bus.subscribe(Ids.next(), others::add);
        bus.signal(merchant, new Signal(Topic.ORDERS, "o1"));
        subscription.close();
        bus.signal(merchant, new Signal(Topic.ORDERS, "o2"));
        assertThat(mine).containsExactly(new Signal(Topic.ORDERS, "o1"));
        assertThat(others).isEmpty();
    }

    @Test
    void signalsEncodeAsTopicAndId() {
        assertThat(new Signal(Topic.MESSAGE, "t1").encode()).isEqualTo("message:t1");
        assertThat(Signal.decode("kitchen")).isEqualTo(new Signal(Topic.KITCHEN, null));
        assertThat(Signal.decode("orders:o1")).isEqualTo(new Signal(Topic.ORDERS, "o1"));
        assertThat(Signal.decode("unknown:x")).isNull();
    }
}
