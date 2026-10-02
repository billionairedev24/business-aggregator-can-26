package ca.northline.worker.events;

import ca.northline.worker.notifications.DeferredNotifications;
import ca.northline.worker.notifications.DeferredRequeue;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
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
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual DLQ replay (S-26, S-115; docs/runbooks/events.md § DLQ), without starting the consumers:
 *
 * <pre>
 * # a Kafka .dlq — list is the dry run; replay needs --actor and --reason (one platform audit entry per run)
 * ./gradlew :worker:dlqReplay --args='list   --topic=payments.payout.dlq --group=notifications'
 * ./gradlew :worker:dlqReplay --args='replay --topic=payments.payout.dlq --group=notifications
 *     [--event=01J…[,01J…]] [--type=payments.payout_failed] [--since=2026-10-02T08:00:00Z|6h] [--until=…]
 *     [--failure=timeout] [--limit=100] [--rate=20] [--force] --actor=you@northline.ca --reason=INC-42'
 * # the deferred notifications given up after their attempts (messaging.deferred_notifications, dead rows)
 * ./gradlew :worker:dlqReplay --args='list   --deferred [--channel=sms|push|email] [--type=…] [--event=…] [--since=…]'
 * ./gradlew :worker:dlqReplay --args='replay --deferred … [--at=2026-10-03T13:00:00Z] --actor=… --reason=…'
 * java -cp @/app/jib-classpath-file ca.northline.worker.events.DlqReplayCommand replay --topic=… --group=…   # worker image
 * </pre>
 *
 * {@code --since}/{@code --until} take an instant or an age ({@code 30m}, {@code 6h}, {@code 2d}). It uses the
 * worker's Kafka and database settings (environment, as the Deployment has them); {@code --deferred} needs only the
 * database.
 */
@Slf4j
public final class DlqReplayCommand {

    private static final Pattern AGE = Pattern.compile("(\\d+)([smhd])");

    private DlqReplayCommand() {}

    public static void main(String[] args) {
        int status;
        try {
            if (Arrays.asList(args).contains("--deferred")) {
                runDeferred(args);
            } else {
                run(args);
            }
            status = 0;
        } catch (RuntimeException e) {
            log.error("DLQ replay: {}", e.getMessage(), e);
            status = 1;
        }
        System.exit(status);
    }

    /** A Kafka {@code .dlq}. */
    public static java.util.List<DlqReplay.Entry> run(String... args) {
        var dryRun = dryRun(args);
        var clock = Clock.systemUTC();
        var request = new DlqReplay.Request(
                option(args, "topic")
                        .orElseThrow(() -> new IllegalArgumentException("--topic=<topic>.dlq is required")),
                option(args, "group")
                        .orElseThrow(() -> new IllegalArgumentException("--group=<consumer group> is required")),
                new DlqReplay.Filter(
                        option(args, "event").map(v -> Set.of(v.split(","))).orElse(Set.of()),
                        option(args, "type"),
                        option(args, "since").map(v -> time(v, clock)),
                        option(args, "until").map(v -> time(v, clock)),
                        option(args, "failure")),
                option(args, "limit").map(Integer::parseInt).orElse(100),
                dryRun,
                Arrays.asList(args).contains("--force"),
                option(args, "rate").map(Double::parseDouble).orElse(DlqReplay.DEFAULT_RATE),
                operator(args, dryRun));
        try (var context = context(args)) {
            var kafka = context.getBean(KafkaProperties.class);
            var consumerProps = new HashMap<>(kafka.buildConsumerProperties());
            consumerProps.remove(ConsumerConfig.GROUP_ID_CONFIG);
            consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            try (var consumer =
                            new KafkaConsumer<>(consumerProps, new StringDeserializer(), new ByteArrayDeserializer());
                    var producer = new KafkaProducer<>(
                            kafka.buildProducerProperties(), new StringSerializer(), new ByteArraySerializer())) {
                var jdbc = context.getBean(JdbcClient.class);
                var processed = new ProcessedEvents(jdbc, clock);
                var entries = new DlqReplay(
                                consumer,
                                producer,
                                processed,
                                context.getBean(TransactionOperations.class),
                                new OperatorAudit(jdbc, clock))
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

    /** The dead rows of {@code messaging.deferred_notifications}. */
    public static DeferredRequeue.Result runDeferred(String... args) {
        var dryRun = dryRun(args);
        var clock = Clock.systemUTC();
        var request = new DeferredRequeue.Request(
                new DeferredNotifications.DeadFilter(
                        option(args, "channel"),
                        option(args, "type"),
                        option(args, "event"),
                        option(args, "since").map(v -> time(v, clock)),
                        option(args, "until").map(v -> time(v, clock))),
                option(args, "limit").map(Integer::parseInt).orElse(100),
                dryRun,
                option(args, "at").map(Instant::parse).orElse(clock.instant()),
                operator(args, dryRun));
        try (var context = context(args)) {
            var jdbc = context.getBean(JdbcClient.class);
            var result = new DeferredRequeue(
                            new DeferredNotifications(jdbc, JsonMapper.builder().build()),
                            context.getBean(TransactionOperations.class),
                            new OperatorAudit(jdbc, clock))
                    .run(request);
            result.rows()
                    .forEach(r -> log.info(
                            "{} deferred {} {} event={} type={} user={} dead since {} after {} attempt(s) ({})",
                            dryRun ? "WOULD_REQUEUE" : "REQUEUED",
                            r.id(),
                            r.channel(),
                            r.eventId(),
                            r.eventType(),
                            r.userId(),
                            r.deadAt(),
                            r.attempts(),
                            r.lastError()));
            log.info(
                    "Deferred notifications: {} dead row(s) matched, {} requeued (due {})",
                    result.rows().size(),
                    result.requeued(),
                    request.dueAt());
            return result;
        }
    }

    private static boolean dryRun(String[] args) {
        var words = Arrays.stream(args).filter(a -> !a.startsWith("--")).toList();
        var command = words.isEmpty() ? "list" : words.getFirst();
        if (!command.equals("list") && !command.equals("replay")) {
            throw new IllegalArgumentException("Unknown command " + command + ": list | replay");
        }
        return command.equals("list");
    }

    private static Optional<OperatorAudit.Operator> operator(String[] args, boolean dryRun) {
        var actor = option(args, "actor");
        var reason = option(args, "reason");
        if (actor.isEmpty() && reason.isEmpty() && dryRun) {
            return Optional.empty();
        }
        return Optional.of(new OperatorAudit.Operator(actor.orElse(""), reason.orElse("")));
    }

    /** An ISO-8601 instant, or an age ({@code 30m}, {@code 6h}, {@code 2d}) counted back from now. */
    static Instant time(String value, Clock clock) {
        var age = AGE.matcher(value);
        if (age.matches()) {
            var amount = Long.parseLong(age.group(1));
            var unit = switch (age.group(2)) {
                case "s" -> ChronoUnit.SECONDS;
                case "m" -> ChronoUnit.MINUTES;
                case "h" -> ChronoUnit.HOURS;
                default -> ChronoUnit.DAYS;
            };
            return clock.instant().minus(amount, unit);
        }
        return Instant.parse(value);
    }

    private static ConfigurableApplicationContext context(String[] args) {
        return new SpringApplicationBuilder(CommandContext.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run(args);
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
