package ca.northline.worker.notifications;

import static ca.northline.worker.support.WorkerContainers.KAFKA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.northline.email.EmailMessage;
import ca.northline.sms.SmsDeliveryFailed;
import ca.northline.worker.events.EventProcessing;
import ca.northline.worker.support.Events;
import ca.northline.worker.support.NotificationTestBeans;
import ca.northline.worker.support.WorkerContainers;
import ca.northline.worker.support.WorkerIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-27 end to end: domain events on Kafka → the notifications consumer → email / SMS / push fakes, with the team, the
 * contacts and the preferences in PostgreSQL as the api writes them.
 */
class NotificationsConsumerTest extends WorkerIntegrationTest {

    static final Instant QUIET_NIGHT = Instant.parse("2026-10-01T05:00:00Z"); // 23:00 in Edmonton (MDT)
    static final Instant NEXT_MORNING = Instant.parse("2026-10-01T13:30:00Z"); // 07:30 in Edmonton
    static final AtomicLong NUMBERS = new AtomicLong(System.nanoTime() % 1_000_000);
    static KafkaProducer<String, byte[]> producer;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    NotificationTestBeans.MutableClock clock;

    @Autowired
    NotificationTestBeans.Emails emails;

    @Autowired
    NotificationTestBeans.Texts texts;

    @Autowired
    NotificationTestBeans.Pushes pushes;

    @Autowired
    Notifier notifier;

    @Autowired
    MeterRegistry meters;

    @BeforeAll
    static void producer() {
        WorkerContainers.start();
        producer = new KafkaProducer<>(Events.producerConfig(KAFKA.getBootstrapServers()));
    }

    @AfterAll
    static void close() {
        producer.close();
    }

    @AfterEach
    void noon() {
        clock.set(NotificationTestBeans.NOON_IN_EDMONTON);
    }

    @Test
    void payoutFailed_emailsAndPushesTheFinanceTeamOnce_inTheirLanguage_perTheDefaults() throws Exception {
        var team = team();
        var owner = team.member("owner", "en-CA");
        var bookkeeper = team.member("bookkeeper", "fr-CA");
        var technician = team.member("technician", "en-CA");
        var id = Events.id();

        publish("payments.payout", id, "payments.payout_failed", payoutFailed(id, team.merchantId()));
        publish("payments.payout", id, "payments.payout_failed", payoutFailed(id, team.merchantId())); // redelivered

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(emails.to(owner.email())).hasSize(1);
            assertThat(emails.to(bookkeeper.email())).hasSize(1);
            assertThat(pushes.to(owner.id())).hasSize(1);
            assertThat(pushes.to(bookkeeper.id())).hasSize(1);
        });
        awaitProcessedTwice(id);
        assertThat(emails.to(owner.email())).hasSize(1);
        assertThat(emails.to(technician.email())).isEmpty(); // technicians don't get money notices
        assertThat(pushes.to(technician.id())).isEmpty();
        assertThat(texts.to(owner.phone())).isEmpty(); // the payout row's SMS cell is off by default

        EmailMessage english = emails.to(owner.email()).getFirst();
        assertThat(english.subject()).isEqualTo("Your payout of $814.37 was returned — " + team.name());
        assertThat(english.text()).contains("Bank’s reason: account_closed", "/b/" + team.merchantId() + "/payouts");
        assertThat(english.headers().get(EmailMessage.LIST_UNSUBSCRIBE)).contains("/api/v1/email/unsubscribe?t=");
        assertThat(emails.to(bookkeeper.email()).getFirst().subject().replaceAll("[\\u00a0\\u202f]", " "))
                .isEqualTo("Votre versement de 814,37 $ a été retourné — " + team.name());
        assertThat(pushes.to(bookkeeper.id()).getFirst().body()).startsWith("Northline : votre banque a retourné");
    }

    @Test
    void eachMembersMatrixDecides() throws Exception {
        var team = team();
        var owner = team.member("owner", "en-CA");
        var other = team.member("owner", "en-CA");
        prefs(owner.id(), """
                {"payout": {"email": false, "sms": true, "push": false}, "dispute": {"sms": false}}""");
        var payout = Events.id();
        var dispute = Events.id();

        publish("payments.payout", payout, "payments.payout_failed", payoutFailed(payout, team.merchantId()));
        publish("payments.dispute", dispute, "payments.dispute_updated", disputeOpened(dispute, team.merchantId()));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(texts.to(owner.phone())).hasSize(1);
            assertThat(emails.to(other.email())).hasSize(1);
            assertThat(texts.to(other.phone())).hasSize(1); // dispute SMS: on by default
        });
        awaitProcessed(payout);
        awaitProcessed(dispute);
        assertThat(texts.to(owner.phone()).getFirst().body())
                .isEqualTo("Northline: your bank returned the payout of $814.37 for " + team.name()
                        + ". The money is back in your balance. Check your bank details in Payouts.");
        assertThat(emails.to(owner.email())).isEmpty(); // payout email off
        assertThat(pushes.to(owner.id())).hasSize(1); // dispute push on (default); payout push off
        assertThat(pushes.to(owner.id()).getFirst().title()).startsWith("Dispute DS-");
        assertThat(texts.to(other.phone()).getFirst().body()).contains("new dispute DS-", "Respond by");
    }

    @Test
    void quietHoursHoldSmsAndPushUntilMorning_emailIsNotHeld() throws Exception {
        clock.set(QUIET_NIGHT);
        var team = team();
        var owner = team.member("owner", "en-CA");
        prefs(owner.id(), """
                {"payout": {"email": true, "sms": true, "push": true}}""");
        var id = Events.id();

        publish("payments.payout", id, "payments.payout_failed", payoutFailed(id, team.merchantId()));
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(emails.to(owner.email())).hasSize(1));
        awaitProcessed(id);
        assertThat(texts.to(owner.phone())).isEmpty();
        assertThat(pushes.to(owner.id())).isEmpty();
        assertThat(deferred(owner.id())).isEqualTo(2);

        notifier.sendDue(50); // still night: nothing is due
        assertThat(texts.to(owner.phone())).isEmpty();

        clock.set(NEXT_MORNING);
        notifier.sendDue(50);
        notifier.sendDue(50);
        assertThat(texts.to(owner.phone())).hasSize(1);
        assertThat(pushes.to(owner.id())).hasSize(1);
        assertThat(deferred(owner.id())).isZero();
    }

    @Test
    void bankAccountChange_textsOwnersRightAway_evenAtNight_andWhateverTheMatrix() throws Exception {
        clock.set(QUIET_NIGHT);
        var team = team();
        var owner = team.member("owner", "fr-CA");
        var bookkeeper = team.member("bookkeeper", "en-CA");
        prefs(owner.id(), """
                {"payout": {"email": false, "sms": false, "push": false}, "dispute": {"sms": false}}""");
        var id = Events.id();

        publish("payments.payout_account", id, "payments.payout_account_changed", bankChange(id, team.merchantId()));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(texts.to(owner.phone())).hasSize(1));
        awaitProcessed(id);
        assertThat(texts.to(owner.phone()).getFirst().body())
                .startsWith("Northline : le compte bancaire de versement de " + team.name() + " change.");
        assertThat(texts.to(bookkeeper.phone())).isEmpty();
        assertThat(deferred(owner.id())).isZero();
        assertThat(emails.to(owner.email())).isEmpty(); // the api emails bank changes (S-13), not the worker
    }

    @Test
    void providerOutage_isRetried_andOnlyTheMissingTextIsSent() throws Exception {
        var team = team();
        var first = team.member("owner", "en-CA");
        var second = team.member("owner", "en-CA");
        texts.fail(first.phone(), SmsDeliveryFailed.Kind.PROVIDER_UNAVAILABLE, 1);
        var id = Events.id();

        publish("payments.dispute", id, "payments.dispute_updated", disputeOpened(id, team.merchantId()));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(texts.to(first.phone())).hasSize(1));
        awaitProcessed(id);
        assertThat(texts.to(second.phone())).hasSize(1);
        assertThat(pushes.to(second.id())).hasSize(1);
    }

    @Test
    void providerDown_retriesThenDeadLetters() throws Exception {
        var team = team();
        var owner = team.member("owner", "en-CA");
        texts.fail(owner.phone(), SmsDeliveryFailed.Kind.PROVIDER_UNAVAILABLE, 99);
        var before = deadLettered();
        var id = Events.id();

        publish("payments.dispute", id, "payments.dispute_updated", disputeOpened(id, team.merchantId()));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(deadLettered()).isEqualTo(before + 1));
        assertThat(texts.to(owner.phone())).isEmpty();
        assertThat(pushes.to(owner.id())).hasSize(1); // sent on the first attempt, not repeated by the retries
        assertThat(claims("sms", id + ":" + owner.id())).isZero(); // released: a DLQ replay sends it
        assertThat(claims("notifications", id)).isZero();
    }

    @Test
    void anUndeliverableNumberIsNotRetried() throws Exception {
        var team = team();
        var owner = team.member("owner", "en-CA");
        texts.fail(owner.phone(), SmsDeliveryFailed.Kind.UNDELIVERABLE_NUMBER, 99);
        var id = Events.id();

        publish("payments.dispute", id, "payments.dispute_updated", disputeOpened(id, team.merchantId()));

        awaitProcessed(id);
        assertThat(texts.to(owner.phone())).isEmpty();
        assertThat(claims("sms", id + ":" + owner.id())).isEqualTo(1); // kept: never tried again
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    record Member(String id, String email, String phone) {}

    final class Team {
        private final String merchantId = "m_" + Events.id();
        private final String name = "Prairie Wrench " + merchantId.substring(20);

        Team() {
            jdbc.sql("insert into merchants.merchants (id, display_name, legal_name) values (:id, :name, :name)")
                    .param("id", merchantId)
                    .param("name", name)
                    .update();
        }

        String merchantId() {
            return merchantId;
        }

        String name() {
            return name;
        }

        Member member(String role, String locale) {
            var id = "u_" + Events.id();
            var email = id.toLowerCase(java.util.Locale.ROOT) + "@example.com";
            var phone = "+1587555" + String.format("%04d", NUMBERS.incrementAndGet() % 10_000);
            jdbc.sql("""
                            insert into identity.users (id, email, phone, first_name, last_name, display_name, locale,
                                                        status)
                            values (:id, :email, :phone, 'Sam', 'Lee', 'Sam Lee', :locale, 'active')""")
                    .param("id", id)
                    .param("email", email)
                    .param("phone", phone)
                    .param("locale", locale)
                    .update();
            jdbc.sql("insert into merchants.merchant_members (merchant_id, user_id, role) values (:m, :u, :r)")
                    .param("m", merchantId)
                    .param("u", id)
                    .param("r", role)
                    .update();
            return new Member(id, email, phone);
        }
    }

    Team team() {
        return new Team();
    }

    void prefs(String userId, String matrix) {
        jdbc.sql("""
                        insert into messaging.notification_prefs (user_id, matrix, quiet_from, quiet_to)
                        values (:u, cast(:m as jsonb), '21:00', '07:00')""").param("u", userId).param("m", matrix).update();
    }

    static void publish(String topic, String id, String type, String json) throws Exception {
        producer.send(Events.record(topic, id, type, 1, json)).get();
    }

    void awaitProcessed(String eventId) {
        await().atMost(Duration.ofSeconds(30)).until(() -> claims("notifications", eventId) == 1);
    }

    void awaitProcessedTwice(String eventId) {
        awaitProcessed(eventId);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> meters
                                .find(EventProcessing.CONSUMED)
                                .tag("consumer", "notifications")
                                .tag("outcome", "duplicate")
                                .counters()
                                .stream()
                                .mapToDouble(c -> c.count())
                                .sum()
                        >= 1);
    }

    int claims(String consumer, String key) {
        return jdbc.sql("select count(*) from events.processed_events where consumer = :c and event_id = :k")
                .param("c", consumer)
                .param("k", key)
                .query(Integer.class)
                .single();
    }

    int deferred(String userId) {
        return jdbc.sql("select count(*) from messaging.deferred_notifications where user_id = :u")
                .param("u", userId)
                .query(Integer.class)
                .single();
    }

    double deadLettered() {
        return meters.find(EventProcessing.DEAD_LETTERED).tag("consumer", "notifications").counters().stream()
                .mapToDouble(c -> c.count())
                .sum();
    }

    static String payoutFailed(String id, String merchantId) {
        return Events.payoutFailed(id, merchantId);
    }

    static String disputeOpened(String id, String merchantId) {
        return """
                {"eventId":"%s","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"dp_%s","merchantId":"%s",\
                "caseNumber":"DS-%s","change":"opened","amountCents":38900,"respondBy":"2026-10-03T15:00:00Z"}""".formatted(id, id, merchantId, id.substring(22));
    }

    static String bankChange(String id, String merchantId) {
        return """
                {"eventId":"%s","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"pa_%s","merchantId":"%s",\
                "phase":"requested","effectiveAt":"2026-10-01T15:00:00Z"}""".formatted(id, id, merchantId);
    }
}
