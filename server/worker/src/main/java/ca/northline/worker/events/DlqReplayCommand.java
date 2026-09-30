package ca.northline.worker.events;

import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Manual DLQ replay (S-26; docs/runbooks/events.md § DLQ), without starting the consumers:
 *
 * <pre>
 * ./gradlew :worker:dlqReplay --args='list   --topic=payments.payout.dlq --group=notifications'
 * ./gradlew :worker:dlqReplay --args='replay --topic=payments.payout.dlq --group=notifications [--event=01J…] [--limit=100] [--force]'
 * java -cp @/app/jib-classpath-file ca.northline.worker.events.DlqReplayCommand replay --topic=… --group=…   # worker image
 * </pre>
 *
 * {@code list} = dry run. It uses the worker's Kafka and database settings (environment, as the Deployment has them).
 */
@Slf4j
public final class DlqReplayCommand {

    private DlqReplayCommand() {}

    public static void main(String[] args) {
        int status;
        try {
            run(args);
            status = 0;
        } catch (RuntimeException e) {
            log.error("DLQ replay: {}", e.getMessage(), e);
            status = 1;
        }
        System.exit(status);
    }

    public static java.util.List<DlqReplay.Entry> run(String... args) {
        var words = Arrays.stream(args).filter(a -> !a.startsWith("--")).toList();
        var command = words.isEmpty() ? "list" : words.getFirst();
        if (!command.equals("list") && !command.equals("replay")) {
            throw new IllegalArgumentException("Unknown command " + command + ": list | replay");
        }
        var request = new DlqReplay.Request(
                option(args, "topic")
                        .orElseThrow(() -> new IllegalArgumentException("--topic=<topic>.dlq is required")),
                option(args, "group")
                        .orElseThrow(() -> new IllegalArgumentException("--group=<consumer group> is required")),
                option(args, "event"),
                option(args, "limit").map(Integer::parseInt).orElse(100),
                command.equals("list"),
                Arrays.asList(args).contains("--force"));
        try (var context = new SpringApplicationBuilder(CommandContext.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run(args)) {
            var kafka = context.getBean(KafkaProperties.class);
            var consumerProps = new HashMap<>(kafka.buildConsumerProperties());
            consumerProps.remove(ConsumerConfig.GROUP_ID_CONFIG);
            consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            try (var consumer =
                            new KafkaConsumer<>(consumerProps, new StringDeserializer(), new ByteArrayDeserializer());
                    var producer = new KafkaProducer<>(
                            kafka.buildProducerProperties(), new StringSerializer(), new ByteArraySerializer())) {
                var processed = new ProcessedEvents(context.getBean(JdbcClient.class), Clock.systemUTC());
                var entries = new DlqReplay(consumer, producer, processed, context.getBean(TransactionOperations.class))
                        .run(request);
                entries.forEach(e -> log.info(
                        "{} {} event={} type={} → {} ({})",
                        e.status(),
                        e.dlqPosition(),
                        e.eventId(),
                        e.type(),
                        e.originalTopic(),
                        e.reason()));
                log.info("DLQ {} group {}: {} record(s)", request.dlqTopic(), request.group(), entries.size());
                return entries;
            }
        }
    }

    private static Optional<String> option(String[] args, String name) {
        return Arrays.stream(args)
                .filter(a -> a.startsWith("--" + name + "="))
                .map(a -> a.substring(name.length() + 3))
                .filter(v -> !v.isBlank())
                .findFirst();
    }

    /** Kafka, a data source and transactions only: no listeners, web server, Valkey or Elasticsearch. */
    @ImportAutoConfiguration({
        KafkaAutoConfiguration.class,
        DataSourceAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        JdbcClientAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class
    })
    static class CommandContext {}
}
