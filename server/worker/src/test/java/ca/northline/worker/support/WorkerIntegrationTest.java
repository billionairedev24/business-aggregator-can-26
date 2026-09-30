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
@Import(TestConsumers.class)
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
        registry.add("spring.kafka.consumer.properties.metadata.max.age.ms", () -> "1000");
    }
}
