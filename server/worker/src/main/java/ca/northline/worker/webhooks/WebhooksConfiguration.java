package ca.northline.worker.webhooks;

import ca.northline.platform.EgressPolicy;
import ca.northline.platform.HostResolver;
import ca.northline.platform.WebhookSecretBox;
import ca.northline.worker.events.EventSchemas;
import ca.northline.worker.notifications.Notifier;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.security.SecureRandom;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;

/** Wiring of partner webhook delivery (S-33): payloads, store, SSRF-safe transport, dispatcher and its jobs. */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhooksConfiguration {

    @Bean
    WebhookPayloads webhookPayloads(JsonMapper json) {
        return new WebhookPayloads(json, EventSchemas.fromClasspath(json, "classpath*:webhooks/*.schema.json"));
    }

    @Bean
    WebhookStore webhookStore(JdbcClient jdbc) {
        return new WebhookStore(jdbc);
    }

    @Bean
    WebhookFanOut webhookFanOut(WebhookPayloads payloads, WebhookStore store, Clock clock) {
        return new WebhookFanOut(payloads, store, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    HostResolver hostResolver() {
        return HostResolver.SYSTEM;
    }

    /**
     * {@code allowLocal} (http + loopback) exists for local development and the tests; the cloud profiles refuse to
     * start with it.
     */
    @Bean
    EgressPolicy webhookEgressPolicy(WebhookProperties settings, Environment environment) {
        if (settings.allowLocal() && environment.acceptsProfiles(Profiles.of("cloud"))) {
            throw new IllegalStateException("northline.webhooks.allow-local must be false outside local development");
        }
        return new EgressPolicy(settings.allowLocal());
    }

    @Bean(destroyMethod = "close")
    HttpWebhookTransport webhookTransport(EgressPolicy policy, HostResolver resolver, WebhookProperties settings) {
        return new HttpWebhookTransport(policy, resolver, settings);
    }

    @Bean
    WebhookDispatcher webhookDispatcher(
            WebhookStore store,
            WebhookTransport transport,
            WebhookPayloads payloads,
            WebhookProperties settings,
            Environment environment,
            TransactionOperations transactions,
            Clock clock,
            MeterRegistry meters) {
        var box = WebhookSecretBox.of(
                        settings.secretKey(),
                        settings.secretKeyId(),
                        settings.secretPreviousKeys(),
                        !environment.acceptsProfiles(Profiles.of("cloud")))
                .orElse(null);
        if (box == null) {
            log.error("WEBHOOK_SECRET_KEY is not set: webhook deliveries fail until it is");
        }
        return new WebhookDispatcher(
                store,
                transport,
                payloads,
                new RetrySchedule(settings.retry(), new SecureRandom()),
                box,
                settings,
                transactions,
                clock,
                meters);
    }

    @Bean
    WebhookDisabledNotices webhookDisabledNotices(WebhookStore store, Notifier notifier, JsonMapper json, Clock clock) {
        return new WebhookDisabledNotices(store, notifier, json, clock);
    }

    @Bean
    WebhookJobs webhookJobs(
            WebhookDispatcher dispatcher,
            WebhookDisabledNotices notices,
            WebhookStore store,
            WebhookProperties settings,
            Clock clock) {
        return new WebhookJobs(dispatcher, notices, store, settings, clock);
    }

    /** The dispatcher tick (every second), the owners' emails (every minute) and the delivery-log purge (nightly). */
    @Slf4j
    static class WebhookJobs {
        private final WebhookDispatcher dispatcher;
        private final WebhookDisabledNotices notices;
        private final WebhookStore store;
        private final WebhookProperties settings;
        private final Clock clock;

        WebhookJobs(
                WebhookDispatcher dispatcher,
                WebhookDisabledNotices notices,
                WebhookStore store,
                WebhookProperties settings,
                Clock clock) {
            this.dispatcher = dispatcher;
            this.notices = notices;
            this.store = store;
            this.settings = settings;
            this.clock = clock;
        }

        @Scheduled(
                fixedDelayString = "${northline.webhooks.poll-every:1s}",
                initialDelayString = "${northline.webhooks.initial-delay:10s}")
        void dispatch() {
            dispatcher.dispatch();
        }

        @Scheduled(
                fixedDelayString = "${northline.webhooks.notify-every:60s}",
                initialDelayString = "${northline.webhooks.initial-delay:10s}")
        void notifyOwners() {
            notices.send();
        }

        @Scheduled(cron = "${northline.webhooks.purge-cron:0 37 3 * * *}", zone = "${northline.region.platform-zone}")
        void purge() {
            var deleted = store.purge(clock.instant().minus(settings.logRetention()));
            log.info("Purged {} webhook deliveries older than {}", deleted, settings.logRetention());
        }

        @PreDestroy
        void stop() {
            dispatcher.stop();
        }
    }
}
