package ca.northline.worker.events;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wiring of the consumer framework: schemas, parser, dedupe, processing, graceful shutdown of the listener containers
 * and the purge of old dedupe claims.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class EventsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EventSchemas eventSchemas(JsonMapper json) {
        var schemas = EventSchemas.fromClasspath(json);
        log.info("Event schemas: {} loaded", schemas.keys().size());
        return schemas;
    }

    @Bean
    EnvelopeParser envelopeParser(JsonMapper json, EventSchemas schemas) {
        return new EnvelopeParser(json, schemas);
    }

    @Bean
    ProcessedEvents processedEvents(JdbcClient jdbc, Clock clock) {
        return new ProcessedEvents(jdbc, clock);
    }

    @Bean
    EventProcessing eventProcessing(
            EnvelopeParser parser,
            ProcessedEvents processed,
            TransactionOperations transactions,
            MeterRegistry meters) {
        return new EventProcessing(parser, processed, transactions, meters);
    }

    /**
     * Graceful shutdown: on SIGTERM the containers stop polling, let the record in hand finish (its handler, its
     * dedupe claim and the offset commit) for up to {@code northline.events.shutdown-timeout}, then close. The chart gives the pod
     * 45 s (terminationGracePeriodSeconds), preStop sleep included.
     */
    @Bean
    ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>> gracefulShutdown(
            @Value("${northline.events.shutdown-timeout:20s}") Duration timeout) {
        return container -> {
            container.getContainerProperties().setShutdownTimeout(timeout.toMillis());
            container.getContainerProperties().setStopImmediate(false);
        };
    }

    @Bean
    ProcessedEventsPurge processedEventsPurge(
            ProcessedEvents processed, @Value("${northline.events.processed-retention:60d}") Duration retention) {
        return new ProcessedEventsPurge(processed, retention);
    }

    /**
     * Deletes dedupe claims older than the retention (60 days by default: longer than any topic's retention — the DLQ
     * keeps 30 days — and the api's outbox resubmission, so no redelivery can outlive its claim). Every replica may
     * run it; the delete is idempotent.
     */
    @Slf4j
    static class ProcessedEventsPurge {
        private final ProcessedEvents processed;
        private final Duration retention;

        ProcessedEventsPurge(ProcessedEvents processed, Duration retention) {
            this.processed = processed;
            this.retention = retention;
        }

        @Scheduled(cron = "${northline.events.purge-cron:0 17 3 * * *}", zone = "${northline.region.platform-zone}")
        void purge() {
            var deleted = processed.purgeOlderThan(retention);
            log.info("Purged {} processed-event claims older than {}", deleted, retention);
        }
    }
}
