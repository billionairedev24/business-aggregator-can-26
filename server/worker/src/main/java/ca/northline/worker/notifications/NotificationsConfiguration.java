package ca.northline.worker.notifications;

import ca.northline.email.Mailer;
import ca.northline.email.UnsubscribeTokens;
import ca.northline.sms.SmsTransport;
import ca.northline.sms.SmsTransportConfiguration;
import ca.northline.worker.events.ProcessedEvents;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wiring of the notifications consumer: the shared email ({@code server/email}, auto-configured) and SMS
 * ({@code server/sms}, {@link SmsTransportConfiguration}) libraries, the push stub, the team read model and the job
 * that sends notifications held back by quiet hours.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
@Import(SmsTransportConfiguration.class)
public class NotificationsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PushSender pushSender() {
        return PushSender.LOGGING;
    }

    @Bean
    Preferences.Defaults notificationDefaults(JsonMapper json) {
        return Preferences.Defaults.load(json);
    }

    @Bean
    Recipients recipients(
            JdbcClient jdbc,
            JsonMapper json,
            Preferences.Defaults defaults,
            @Value("${northline.region.default-province:}") String defaultProvince,
            @Value("${northline.region.platform-zone}") ZoneId platformZone) {
        return new JdbcRecipients(
                jdbc, json, defaults, new JdbcRecipients.RegionSettings(defaultProvince.strip(), platformZone));
    }

    @Bean
    DeferredNotifications deferredNotifications(JdbcClient jdbc, JsonMapper json) {
        return new DeferredNotifications(jdbc, json);
    }

    @Bean
    Deliveries deliveries(
            Mailer mailer,
            SmsTransport sms,
            PushSender push,
            ProcessedEvents claims,
            PlatformTransactionManager transactions,
            NotificationProperties links,
            Environment environment,
            MeterRegistry meters) {
        var separately = new TransactionTemplate(transactions);
        separately.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new Deliveries(
                mailer,
                sms,
                push,
                claims,
                separately,
                links,
                UnsubscribeTokens.forEnvironment(links.unsubscribeKey(), environment),
                meters);
    }

    @Bean
    Notifier notifier(Recipients recipients, Deliveries deliveries, DeferredNotifications deferred, Clock clock) {
        return new Notifier(recipients, deliveries, deferred, clock);
    }

    @Bean
    DeferredNotificationsJob deferredNotificationsJob(Notifier notifier, TransactionOperations transactions) {
        return new DeferredNotificationsJob(notifier, transactions);
    }

    /** Every minute: send what quiet hours held back (replicas share rows through {@code skip locked}). */
    static class DeferredNotificationsJob {
        private final Notifier notifier;
        private final TransactionOperations transactions;

        DeferredNotificationsJob(Notifier notifier, TransactionOperations transactions) {
            this.notifier = notifier;
            this.transactions = transactions;
        }

        @Scheduled(
                fixedDelayString = "${northline.notifications.deferred-every:60s}",
                initialDelayString = "${northline.notifications.deferred-initial-delay:30s}")
        void run() {
            transactions.executeWithoutResult(_ -> notifier.sendDue(50));
        }
    }
}
