package ca.northline.worker.notifications;

import static ca.northline.worker.support.WorkerContainers.KAFKA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.northline.email.EmailMessage;
import ca.northline.worker.support.Events;
import ca.northline.worker.support.NotificationTestBeans;
import ca.northline.worker.support.WorkerContainers;
import ca.northline.worker.support.WorkerIntegrationTest;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-102 end to end: order, booking, quote and refund events on Kafka → the notifications consumer → push / SMS / email
 * fakes for the customer, per their Account › Notifications matrix and quiet hours (S-58/S-59 columns as the api
 * writes them); run events → the courier's push; the evening-before booking reminder.
 */
class CustomerNotificationsTest extends WorkerIntegrationTest {

    /** 22:30 in Toronto, 20:30 in Edmonton (the platform zone of the tests): quiet (22:00–07:00) in Québec only. */
    static final Instant TORONTO_NIGHT = Instant.parse("2026-10-01T02:30:00Z");
    /** 23:00 in Edmonton. */
    static final Instant EDMONTON_NIGHT = Instant.parse("2026-10-01T05:00:00Z");

    static final Instant NEXT_MORNING = Instant.parse("2026-10-01T13:30:00Z");
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
    BookingReminders reminders;

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
    void outForDelivery_pushesTheCustomerWithADeepLink_andEmailsThem_perTheDefaults() throws Exception {
        var amara = customer("en-CA");
        var order = order(amara, "goods");
        var id = Events.id();

        publish("fulfilment.delivery", id, "fulfilment.delivery_picked_up", pickedUp(id, order));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(pushes.to(amara.id())).hasSize(1);
            assertThat(emails.to(amara.email())).hasSize(1);
        });
        awaitProcessed(id);
        var push = pushes.to(amara.id()).getFirst();
        assertThat(push.app()).isEqualTo(PushApp.CONSUMER);
        assertThat(push.title()).isEqualTo("Out for delivery");
        assertThat(push.link()).isEqualTo(URI.create("http://localhost:3000/orders/" + order));
        assertThat(push.data()).containsEntry("orderId", order).containsOnlyKeys("type", "orderId");
        assertThat(push.language()).isNull(); // "same as app": each installation in its own language
        assertThat(texts.to(amara.phone())).isEmpty(); // order updates: no SMS by default

        EmailMessage email = emails.to(amara.email()).getFirst();
        assertThat(email.subject()).isEqualTo("Out for delivery");
        assertThat(email.text()).contains("Your order is out for delivery.", "http://localhost:3000/orders/" + order);
        assertThat(email.headers().get(EmailMessage.LIST_UNSUBSCRIBE)).contains("/api/v1/email/unsubscribe?t=");
        // nothing about the customer goes to the push provider
        assertThat(push.toString()).doesNotContain(amara.email(), amara.phone(), "Amara");
    }

    @Test
    void theCustomersMatrixDecides_andTheirLanguage() throws Exception {
        var kofi = customer("en-CA");
        prefs(kofi.id(), """
                {"order_updates": {"push": false, "sms": true, "email": false}}""", null, "fr");
        var order = order(kofi, "food");
        var id = Events.id();

        publish("orders.order", id, "orders.order_delivered", delivered(id, order, "food"));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(texts.to(kofi.phone())).hasSize(1));
        awaitProcessed(id);
        assertThat(texts.to(kofi.phone()).getFirst().body())
                .isEqualTo("Northline : Votre commande a été livrée. Un problème? Signalez-le depuis la commande.");
        assertThat(pushes.to(kofi.id())).isEmpty();
        assertThat(emails.to(kofi.email())).isEmpty();
    }

    @Test
    void quietHours_holdPushAndSms_inTheZoneOfTheCustomersProvince_emailIsNotHeld() throws Exception {
        clock.set(TORONTO_NIGHT);
        var inQuebec = customer("fr-CA");
        province(inQuebec.id(), "QC");
        prefs(inQuebec.id(), """
                {"refunds_cases": {"push": true, "sms": true, "email": true}}""", null, "fr");
        var platformZone = customer("en-CA"); // no province: the platform zone, still evening
        var quietOff = customer("en-CA");
        province(quietOff.id(), "QC");
        prefs(quietOff.id(), "{}", false, null);
        var merchant = merchant();
        var cases = new String[3];
        var customers = new Member[] {inQuebec, platformZone, quietOff};
        for (int i = 0; i < 3; i++) {
            var id = Events.id();
            cases[i] = refundCase(customers[i], merchant);
            publish("payments.refund", id, "payments.refund_case_updated", refundApproved(id, cases[i], merchant));
            awaitProcessed(id);
        }

        assertThat(emails.to(inQuebec.email())).hasSize(1); // not held
        assertThat(pushes.to(inQuebec.id())).isEmpty();
        assertThat(texts.to(inQuebec.phone())).isEmpty();
        assertThat(deferred(inQuebec.id())).isEqualTo(2);
        assertThat(pushes.to(platformZone.id())).hasSize(1);
        assertThat(pushes.to(quietOff.id())).hasSize(1); // quiet hours turned off

        clock.set(NEXT_MORNING);
        notifier.sendDue(50);
        assertThat(pushes.to(inQuebec.id())).hasSize(1);
        assertThat(pushes.to(inQuebec.id()).getFirst().title())
                .isEqualTo("Remboursement RF-" + cases[0].substring(23) + " approuvé");
        assertThat(texts.to(inQuebec.phone())).hasSize(1);
        assertThat(deferred(inQuebec.id())).isZero();
    }

    @Test
    void aProviderOutage_forACustomer_isRetriedFromTheTable_notTheEventsRetryTopics() throws Exception {
        var amara = customer("en-CA");
        prefs(amara.id(), """
                {"order_updates": {"push": true, "sms": true, "email": false}}""", null, null);
        texts.fail(amara.phone(), ca.northline.sms.SmsDeliveryFailed.Kind.PROVIDER_UNAVAILABLE, 1);
        var order = order(amara, "goods");
        var id = Events.id();

        publish("orders.order", id, "orders.order_delivered", delivered(id, order, "goods"));

        awaitProcessed(id); // processed: the outage doesn't fail the event
        assertThat(pushes.to(amara.id())).hasSize(1);
        assertThat(texts.to(amara.phone())).isEmpty();
        assertThat(deferred(amara.id())).isEqualTo(1);

        clock.set(NotificationTestBeans.NOON_IN_EDMONTON.plus(Duration.ofMinutes(6)));
        notifier.sendDue(50);
        assertThat(texts.to(amara.phone())).hasSize(1);
        assertThat(pushes.to(amara.id())).hasSize(1); // the push isn't repeated
        assertThat(deferred(amara.id())).isZero();
    }

    @Test
    void bookingConfirmed_andQuoteReceived_reachTheCustomer_withTheBusinessName() throws Exception {
        var amara = customer("en-CA");
        var merchant = merchant();
        var booking = booking(amara, merchant, Instant.parse("2026-10-03T16:00:00Z"));
        var request = quoteRequest(amara);
        var confirmed = Events.id();
        var quote = Events.id();

        publish(
                "booking.booking",
                confirmed,
                "booking.booking_confirmed",
                bookingConfirmed(confirmed, booking, merchant, amara.id()));
        publish("booking.quote", quote, "booking.quote_sent", quoteSent(quote, request, merchant));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(pushes.to(amara.id())).hasSize(2));
        var byType = pushes.to(amara.id()).stream()
                .collect(java.util.stream.Collectors.toMap(p -> p.data().get("type"), p -> p));
        assertThat(spaces(byType.get("booking.confirmed").body()))
                .isEqualTo(
                        merchantName(merchant) + " confirmed your booking for Saturday, October 3, 2026 at 10:00 a.m.");
        assertThat(byType.get("booking.confirmed").link())
                .isEqualTo(URI.create("http://localhost:3000/bookings/" + booking));
        assertThat(byType.get("quote.sent").title()).isEqualTo("New quote from " + merchantName(merchant));
        assertThat(byType.get("quote.sent").link()).isEqualTo(URI.create("http://localhost:3000/quotes/qt_" + quote));
        awaitProcessed(confirmed);
        assertThat(texts.to(amara.phone())).hasSize(1); // booking reminders: SMS on by default
        assertThat(emails.to(amara.email())).isEmpty(); // neither row emails by default
    }

    @Test
    void aCourierHearsAboutTheirRun_atAnyHour_whateverTheirPreferences() throws Exception {
        clock.set(EDMONTON_NIGHT);
        var kai = customer("fr-CA");
        prefs(kai.id(), "{}", true, null);
        var courier = courier(kai);
        var assigned = Events.id();
        var changed = Events.id();

        publish("fulfilment.run", assigned, "fulfilment.delivery_assigned", deliveryAssigned(assigned, courier));
        publish("fulfilment.run", changed, "fulfilment.run_changed", runChanged(changed, courier));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(pushes.to(kai.id())).hasSize(2));
        var first = pushes.to(kai.id()).stream()
                .filter(p -> p.data().get("type").equals("run.assigned"))
                .findFirst()
                .orElseThrow();
        assertThat(first.app()).isEqualTo(PushApp.COURIER);
        assertThat(first.language()).isEqualTo("fr");
        assertThat(first.link()).isEqualTo(URI.create("http://localhost:3000/courier/run"));
        assertThat(texts.to(kai.phone())).isEmpty(); // couriers get push only
        assertThat(emails.to(kai.email())).isEmpty();
        assertThat(deferred(kai.id())).isZero();
    }

    @Test
    void theEveningBeforeReminder_goesOutOnce_after18hInTheCustomersZone() {
        var amara = customer("en-CA");
        var merchant = merchant();
        // a booking at 9:00 on Oct 3 in Edmonton (15:00Z); the reminder is due at 18:00 on Oct 2 (00:00Z on Oct 3)
        var booking = booking(amara, merchant, Instant.parse("2026-10-03T15:00:00Z"));
        clock.set(Instant.parse("2026-10-02T23:30:00Z")); // 17:30 in Edmonton
        reminders.run();
        assertThat(pushes.to(amara.id())).isEmpty();

        clock.set(Instant.parse("2026-10-03T00:30:00Z")); // 18:30
        reminders.run();
        reminders.run(); // once
        assertThat(pushes.to(amara.id())).singleElement().satisfies(p -> {
            assertThat(p.title()).isEqualTo("Your booking is tomorrow");
            assertThat(spaces(p.body()))
                    .isEqualTo("Reminder: " + merchantName(merchant)
                            + " is booked for Saturday, October 3, 2026 at 9:00 a.m.");
            assertThat(p.link()).isEqualTo(URI.create("http://localhost:3000/bookings/" + booking));
        });
        assertThat(texts.to(amara.phone())).hasSize(1);
    }

    /** The JDK's locale data puts no-break spaces in times ("9:00 a.m."). */
    static String spaces(String text) {
        return text.replaceAll("[\\u00a0\\u202f]", " ");
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    record Member(String id, String email, String phone) {}

    Member customer(String locale) {
        var id = "u_" + Events.id();
        var email = id.toLowerCase(Locale.ROOT) + "@example.com";
        var phone = "+1587556" + String.format("%04d", NUMBERS.incrementAndGet() % 10_000);
        jdbc.sql("""
                        insert into identity.users (id, email, phone, first_name, last_name, display_name, locale, status)
                        values (:id, :email, :phone, 'Amara', 'Osei', 'Amara Osei', :locale, 'active')""")
                .param("id", id)
                .param("email", email)
                .param("phone", phone)
                .param("locale", locale)
                .update();
        return new Member(id, email, phone);
    }

    void prefs(String userId, String customerMatrix, @Nullable Boolean quietOn, @Nullable String language) {
        jdbc.sql("""
                        insert into messaging.notification_prefs (user_id, customer_matrix, quiet_on, notify_lang)
                        values (:u, cast(:m as jsonb), :q, :l)""")
                .param("u", userId)
                .param("m", customerMatrix)
                .param("q", quietOn)
                .param("l", language)
                .update();
    }

    void province(String userId, String province) {
        jdbc.sql("insert into account.preferences (user_id, province) values (:u, :p)")
                .param("u", userId)
                .param("p", province)
                .update();
    }

    String merchant() {
        var id = "m_" + Events.id();
        jdbc.sql("insert into merchants.merchants (id, display_name, legal_name) values (:id, :name, :name)")
                .param("id", id)
                .param("name", merchantName(id))
                .update();
        return id;
    }

    static String merchantName(String merchantId) {
        return "Prairie Wrench " + merchantId.substring(20);
    }

    String order(Member customer, String type) {
        var id = Events.id();
        jdbc.sql("""
                        insert into orders.orders (id, customer_id, type, state, fulfilment_mode)
                        values (:id, :c, :t, 'picked_up', 'delivery')""").param("id", id).param("c", customer.id()).param("t", type).update();
        return id;
    }

    String booking(Member customer, String merchant, Instant startsAt) {
        var id = Events.id();
        jdbc.sql("""
                        insert into booking.bookings (id, customer_id, merchant_id, type, state, starts_at, ends_at)
                        values (:id, :c, :m, 'home', 'confirmed', :s, :e)""")
                .param("id", id)
                .param("c", customer.id())
                .param("m", merchant)
                .param("s", java.time.OffsetDateTime.ofInstant(startsAt, java.time.ZoneOffset.UTC))
                .param("e", java.time.OffsetDateTime.ofInstant(startsAt.plusSeconds(7200), java.time.ZoneOffset.UTC))
                .update();
        return id;
    }

    String quoteRequest(Member customer) {
        var id = Events.id();
        jdbc.sql("insert into booking.quote_requests (id, customer_id) values (:id, :c)")
                .param("id", id)
                .param("c", customer.id())
                .update();
        return id;
    }

    String refundCase(Member customer, String merchant) {
        var escrow = "es_" + Events.id();
        jdbc.sql("""
                        insert into payments.escrows (id, merchant_id, customer_id, amount_cents, state)
                        values (:id, :m, :c, 4500, 'held')""")
                .param("id", escrow)
                .param("m", merchant)
                .param("c", customer.id())
                .update();
        var id = "rf_" + Events.id();
        jdbc.sql("""
                        insert into payments.refunds (id, escrow_id, merchant_id, case_number, amount_cents, state)
                        values (:id, :e, :m, :n, 4500, 'approved')""")
                .param("id", id)
                .param("e", escrow)
                .param("m", merchant)
                .param("n", "RF-" + id.substring(23))
                .update();
        return id;
    }

    String courier(Member person) {
        var id = "co_" + Events.id();
        jdbc.sql("insert into fulfilment.couriers (id, user_id, vehicle, status) values (:id, :u, 'bike', 'available')")
                .param("id", id)
                .param("u", person.id())
                .update();
        return id;
    }

    static void publish(String topic, String id, String type, String json) throws Exception {
        producer.send(Events.record(topic, id, type, 1, json)).get();
    }

    void awaitProcessed(String eventId) {
        await().atMost(Duration.ofSeconds(30))
                .until(() -> jdbc.sql("select count(*) from events.processed_events where event_id = :k and consumer in"
                                        + " ('notifications', 'personal-notifications')")
                                .param("k", eventId)
                                .query(Integer.class)
                                .single()
                        == 1);
    }

    int deferred(String userId) {
        return jdbc.sql("select count(*) from messaging.deferred_notifications where user_id = :u")
                .param("u", userId)
                .query(Integer.class)
                .single();
    }

    static String pickedUp(String id, String order) {
        return """
                {"eventId":"%s","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"%s","runId":"run_1",\
                "courierId":"co_1"}""".formatted(id, order);
    }

    static String delivered(String id, String order, String type) {
        return """
                {"eventId":"%s","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"%s","orderType":"%s",\
                "proof":"photo"}""".formatted(id, order, type);
    }

    static String refundApproved(String id, String refund, String merchant) {
        return """
                {"eventId":"%s","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"%s","merchantId":"%s",\
                "caseNumber":"RF-%s","change":"approved","amountCents":4500,"respondBy":null}""".formatted(id, refund, merchant, refund.substring(23));
    }

    static String bookingConfirmed(String id, String booking, String merchant, String customer) {
        return """
                {"eventId":"%s","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"%s","merchantId":"%s",\
                "customerId":"%s","bookingType":"home","startsAt":"2026-10-03T16:00:00Z",\
                "endsAt":"2026-10-03T18:00:00Z","priceCents":18900,"depositCents":0}""".formatted(id, booking, merchant, customer);
    }

    static String quoteSent(String id, String request, String merchant) {
        return """
                {"eventId":"%s","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"qt_%s","requestId":"%s",\
                "merchantId":"%s","actorId":"u_1","quoteVersion":1,"totalCents":64500,"depositCents":0,\
                "validUntil":"2026-10-15T15:00:00Z"}""".formatted(id, id, request, merchant);
    }

    static String deliveryAssigned(String id, String courier) {
        return """
                {"eventId":"%s","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"run_%s","courierId":"%s",\
                "orderIds":["o1","o2","o3"],"merchantIds":["m1"],"orderType":"goods"}""".formatted(id, id, courier);
    }

    static String runChanged(String id, String courier) {
        return """
                {"eventId":"%s","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"run_%s","courierId":"%s",\
                "change":"unassigned"}""".formatted(id, id, courier);
    }
}
