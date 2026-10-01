package ca.northline.worker.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Waits for a consumer group's listeners (main topics and retry topics) to own partitions before a test publishes.
 * Otherwise the first test after the context starts spends its time budget on the group join/rebalance, which takes
 * well over five seconds on a loaded machine.
 */
public final class Listeners {

    static final Duration ASSIGNMENT = Duration.ofSeconds(90);

    private Listeners() {}

    public static void awaitAssigned(KafkaListenerEndpointRegistry registry, String groupId) {
        await().atMost(ASSIGNMENT).pollInterval(Duration.ofMillis(200)).until(() -> {
            var containers = registry.getListenerContainers().stream()
                    .filter(c -> String.valueOf(c.getGroupId()).startsWith(groupId)) // + the retry topics' groups
                    .toList();
            return !containers.isEmpty() && containers.stream().allMatch(Listeners::assigned);
        });
    }

    private static boolean assigned(MessageListenerContainer container) {
        var partitions = container.getAssignedPartitions();
        return container.isRunning() && partitions != null && !partitions.isEmpty();
    }
}
