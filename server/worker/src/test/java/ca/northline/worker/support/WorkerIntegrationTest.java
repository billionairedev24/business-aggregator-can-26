package ca.northline.worker.support;

import ca.northline.worker.WorkerApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole worker (every listener) against {@link WorkerContainers}, plus the scripted {@link TestConsumers}.
 * Tests use fresh event ids and never assume empty tables or topics.
 */
@SpringBootTest(classes = WorkerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({TestConsumers.class, NotificationTestBeans.class})
public abstract class WorkerIntegrationTest {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        WorkerContainers.start();
        registry.add("spring.kafka.bootstrap-servers", WorkerContainers.KAFKA::getBootstrapServers);
        registry.add("spring.datasource.url", WorkerContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", WorkerContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", WorkerContainers.POSTGRES::getPassword);
        registry.add("management.health.redis.enabled", () -> "false");
        registry.add("management.health.elasticsearch.enabled", () -> "false");
        // S-43: Elasticsearch 9 with the listings indices of deploy/search; the reconcile sweep only when a test calls
        // it
        registry.add("spring.elasticsearch.uris", WorkerContainers::elasticWithIndices);
        registry.add("northline.search.reconcile.initial-delay", () -> "1h");
        registry.add("northline.search.reconcile.every", () -> "1h");
        // a transient failure under load is retried in 100 ms, not 10 s (same retry topics)
        registry.add("northline.search.retry.delay", () -> "100");
        registry.add("northline.search.retry.multiplier", () -> "2");
        registry.add("northline.search.retry.max-delay", () -> "1000");
        registry.add("spring.kafka.consumer.properties.metadata.max.age.ms", () -> "1000");
        // Notifications: fast retries (same topics: the suffixes don't depend on the delays), the deferred job only
        // when a test calls it, no in-process email retries.
        registry.add("northline.notifications.retry.delay", () -> "100");
        registry.add("northline.notifications.retry.multiplier", () -> "2");
        registry.add("northline.notifications.retry.max-delay", () -> "1000");
        registry.add("northline.notifications.deferred-initial-delay", () -> "1h");
        registry.add("northline.notifications.reminders-initial-delay", () -> "1h"); // S-102: only when a test calls it
        registry.add("northline.email.retry.attempts", () -> "1");
        // Webhooks (S-33): WireMock on http://localhost, a fast dispatcher tick, no jitter (the tests move the clock),
        // short timeouts.
        registry.add("northline.webhooks.allow-local", () -> "true");
        registry.add("northline.webhooks.poll-every", () -> "100ms");
        registry.add("northline.webhooks.notify-every", () -> "200ms");
        registry.add("northline.webhooks.initial-delay", () -> "0s");
        registry.add("northline.webhooks.retry.jitter", () -> "0");
        registry.add("northline.webhooks.connect-timeout", () -> "2s");
        registry.add("northline.webhooks.response-timeout", () -> "3s");
        registry.add("northline.webhooks.total-timeout", () -> "4s");
    }
}
