package ca.northline.worker.webhooks;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.northline.platform.WebhookSecretBox;
import ca.northline.worker.events.EventProcessing;
import ca.northline.worker.support.Events;
import ca.northline.worker.support.NotificationTestBeans;
import ca.northline.worker.support.WorkerContainers;
import ca.northline.worker.support.WorkerIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-33 end to end: domain events on Kafka → the {@code webhooks} consumer group → queued deliveries → the dispatcher →
 * a WireMock partner endpoint; the endpoints, secrets and team in PostgreSQL as the api writes them.
 */
class WebhookDeliveryTest extends WorkerIntegrationTest {

    static final WireMockServer PARTNER = new WireMockServer(wireMockConfig().dynamicPort());
    static final WebhookSecretBox BOX = WebhookSecretBox.of(null, true).orElseThrow();
    static final String SECRET = "whsec_test_not_a_real_secret_0001";
    static final String OLD_SECRET = "whsec_test_not_a_real_secret_0000";
    static final Instant T0 = NotificationTestBeans.NOON_IN_EDMONTON;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static KafkaProducer<String, byte[]> producer;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    NotificationTestBeans.MutableClock clock;

    @Autowired
    NotificationTestBeans.Emails emails;

    @Autowired
    MeterRegistry meters;

    @BeforeAll
    static void start() {
        WorkerContainers.start();
        PARTNER.start();
        producer = new KafkaProducer<>(Events.producerConfig(WorkerContainers.KAFKA.getBootstrapServers()));
    }

    @AfterAll
    static void stop() {
        producer.close();
        PARTNER.stop();
    }

    @BeforeEach
    void noon() {
        clock.set(T0);
    }

    @AfterEach
    void backToNoon() {
        clock.set(T0);
    }

    @Test
    void bookingCompleted_reachesTheSubscribedEndpointOnce_signedWithItsSecret() throws Exception {
        var shop = merchant();
        var subscribed = endpoint(shop, List.of("booking.completed", "payment.released"));
        var otherType = endpoint(shop, List.of("refund.issued"));
        var otherShop = endpoint(merchant(), List.of("booking.completed"));
        ok(subscribed, otherType, otherShop);
        var id = Events.id();
        var duplicates = duplicates("booking.booking_completed");

        publish("booking.booking", id, "booking.booking_completed", bookingCompleted(id, shop));
        publish("booking.booking", id, "booking.booking_completed", bookingCompleted(id, shop)); // redelivered

        await().atMost(Duration.ofSeconds(30)).until(() -> "succeeded".equals(state(subscribed, id)));
        await().atMost(Duration.ofSeconds(30)).until(() -> duplicates("booking.booking_completed") > duplicates);
        Thread.sleep(300); // a second delivery would have been dispatched by now
        var requests = requests(subscribed);
        assertThat(requests).hasSize(1);
        assertThat(requests(otherType)).isEmpty();
        assertThat(requests(otherShop)).isEmpty();

        var request = requests.getFirst();
        assertThat(WebhookSigner.verify(
                        request.getHeader(WebhookSigner.HEADER),
                        request.getBody(),
                        SECRET,
                        T0,
                        WebhookSigner.TOLERANCE))
                .isTrue();
        assertThat(request.getHeader(WebhookSigner.HEADER)).startsWith("t=" + T0.getEpochSecond() + ",v1=");
        assertThat(request.getHeader(WebhookDispatcher.EVENT_ID)).isEqualTo(id);
        assertThat(request.getHeader(WebhookDispatcher.EVENT_TYPE)).isEqualTo("booking.completed");
        assertThat(request.getHeader(WebhookDispatcher.ATTEMPT)).isEqualTo("1");
        assertThat(request.getHeader(WebhookDispatcher.DELIVERY_ID)).isEqualTo(deliveryId(subscribed, id));
        assertThat(map(request.getBodyAsString())).isEqualTo(map("""
                        {"id":"%s","type":"booking.completed","version":1,"createdAt":"2026-09-30T15:00:00Z",
                         "merchantId":"%s","data":{"bookingId":"bk_%s","completedBy":"u_tech","photoCount":2}}""".formatted(id, shop, id)));
        assertThat(delivery(subscribed, id))
                .containsEntry("attempt", 1)
                .containsEntry("status_code", 200)
                .containsEntry("response_snippet", "thanks");
    }

    @Test
    void orderDelivered_reachesEveryShopOnTheOrder_andTheOrdersTopicIsNotDeadLettered() throws Exception {
        var bakery = merchant();
        var butcher = merchant();
        var bakeryHook = endpoint(bakery, List.of("order.delivered"));
        var butcherHook = endpoint(butcher, List.of("order.placed", "order.delivered"));
        var placedOnly = endpoint(bakery, List.of("order.placed"));
        var notOnTheOrder = endpoint(merchant(), List.of("order.delivered"));
        ok(bakeryHook, butcherHook, placedOnly, notOnTheOrder);
        var failed = outcome("orders.order_delivered", "failed") + outcome("orders.order_confirmed", "failed");
        var confirmedBefore = outcome("orders.order_confirmed", "processed");
        var olderBefore = outcome("orders.order_delivered", "processed");
        var id = Events.id();
        var older = Events.id();
        var confirmed = Events.id();

        publish("orders.order", older, "orders.order_delivered", """
                {"eventId":"%s","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"ord_%s","orderType":"goods",
                 "proof":"photo"}""".formatted(
                        older, older)); // published before merchantIds
        publish("orders.order", id, "orders.order_delivered", """
                {"eventId":"%s","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"ord_%s","orderType":"goods",
                 "proof":"photo","merchantIds":["%s","%s"]}""".formatted(id, id, bakery, butcher));
        publish("orders.order", confirmed, "orders.order_confirmed", """
                {"eventId":"%s","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"ord_%s","orderType":"goods"}""".formatted(confirmed, id));

        await().atMost(Duration.ofSeconds(30))
                .until(() -> "succeeded".equals(state(bakeryHook, id)) && "succeeded".equals(state(butcherHook, id)));
        await().atMost(Duration.ofSeconds(30))
                .until(() -> outcome("orders.order_confirmed", "processed") > confirmedBefore
                        && outcome("orders.order_delivered", "processed") >= olderBefore + 2);
        for (var shop : List.of(Map.entry(bakeryHook, bakery), Map.entry(butcherHook, butcher))) {
            var requests = requests(shop.getKey());
            assertThat(requests).hasSize(1);
            assertThat(requests.getFirst().getHeader(WebhookDispatcher.EVENT_TYPE))
                    .isEqualTo("order.delivered");
            assertThat(map(requests.getFirst().getBodyAsString()))
                    .isEqualTo(map("""
                            {"id":"%s","type":"order.delivered","version":1,"createdAt":"2026-09-30T15:00:00Z",
                             "merchantId":"%s","data":{"orderId":"ord_%s","orderType":"goods","proof":"photo"}}""".formatted(id, shop.getValue(), id)));
        }
        assertThat(requests(placedOnly)).isEmpty();
        assertThat(requests(notOnTheOrder)).isEmpty();
        assertThat(outcome("orders.order_delivered", "failed") + outcome("orders.order_confirmed", "failed"))
                .isEqualTo(failed);
        assertThat(deadLettered("orders.order")).isZero();
    }

    @Test
    void failuresAreRetriedWithExponentialBackOff_andEveryAttemptIsLogged() throws Exception {
        var shop = merchant();
        var endpoint = endpoint(shop, List.of("payment.released"));
        PARTNER.stubFor(post(path(endpoint))
                .inScenario(endpoint)
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500).withBody("boom"))
                .willSetStateTo("second"));
        PARTNER.stubFor(post(path(endpoint))
                .inScenario(endpoint)
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503).withBody("busy"))
                .willSetStateTo("third"));
        PARTNER.stubFor(post(path(endpoint))
                .inScenario(endpoint)
                .whenScenarioStateIs("third")
                .willReturn(aResponse().withStatus(200).withBody("ok")));
        var id = Events.id();

        publish("payments.escrow", id, "payments.escrow_released", escrowReleased(id, shop));

        await().atMost(Duration.ofSeconds(30)).until(() -> attempts(endpoint, id) == 1);
        assertThat(delivery(endpoint, id))
                .containsEntry("state", "pending")
                .containsEntry("status_code", 500)
                .containsEntry("next_attempt_at", ts(T0.plusSeconds(30)));
        Thread.sleep(500);
        assertThat(requests(endpoint)).hasSize(1); // nothing before its time

        clock.set(T0.plusSeconds(31));
        await().atMost(Duration.ofSeconds(10)).until(() -> attempts(endpoint, id) == 2);
        assertThat(delivery(endpoint, id))
                .containsEntry("state", "pending")
                .containsEntry("next_attempt_at", ts(T0.plusSeconds(31 + 90)));

        clock.set(T0.plusSeconds(31 + 91));
        await().atMost(Duration.ofSeconds(10)).until(() -> "succeeded".equals(state(endpoint, id)));

        assertThat(jdbc.sql("""
                        select a.attempt, a.status_code, a.response_snippet from developer.webhook_attempts a
                          join developer.webhook_deliveries d on d.id = a.delivery_id
                         where d.endpoint_id = ? and d.event_id = ? order by a.attempt""")
                        .params(endpoint, id)
                        .query((rs, _) -> rs.getInt(1) + ":" + rs.getInt(2) + ":" + rs.getString(3))
                        .list())
                .containsExactly("1:500:boom", "2:503:busy", "3:200:ok");
        assertThat(requests(endpoint))
                .extracting(r -> r.getHeader(WebhookDispatcher.ATTEMPT))
                .containsExactly("1", "2", "3");
        assertThat(health(endpoint)).containsEntry("consecutive_failures", 0).containsEntry("active", true);
    }

    @Test
    void anEndpointFailingForThreeDaysIsTurnedOff_andItsOwnersAreEmailed() throws Exception {
        var shop = merchant();
        var owner = member(shop, "owner");
        var technician = member(shop, "technician");
        var endpoint = endpoint(shop, List.of("refund.issued"));
        PARTNER.stubFor(post(path(endpoint)).willReturn(aResponse().withStatus(503)));
        jdbc.sql("""
                        update developer.webhook_endpoints
                           set failing_since = ?, consecutive_failures = 9 where id = ?""")
                .params(
                        OffsetDateTime.ofInstant(T0.minus(Duration.ofDays(3)).minusSeconds(60), ZoneOffset.UTC),
                        endpoint)
                .update();
        var later = queue(endpoint, shop, T0.plus(Duration.ofHours(1))); // another delivery waiting its turn
        var id = Events.id();

        publish("payments.refund", id, "payments.refund_issued", refundIssued(id, shop));

        await().atMost(Duration.ofSeconds(30))
                .until(() -> !Boolean.TRUE.equals(health(endpoint).get("active")));
        assertThat(health(endpoint))
                .containsEntry("disabled_reason", "failing")
                .containsEntry("consecutive_failures", 10);
        assertThat(state(endpoint, id)).isEqualTo("failed");
        assertThat(jdbc.sql("select state from developer.webhook_deliveries where id = ?")
                        .params(later)
                        .query(String.class)
                        .single())
                .isEqualTo("failed");
        assertThat(jdbc.sql("""
                        select count(*) from developer.audit_log
                         where target_id = ? and action = 'webhook.disabled' and role = 'system'""").params(endpoint).query(Integer.class).single()).isEqualTo(1);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(emails.to(owner)).hasSize(1));
        await().atMost(Duration.ofSeconds(10)).until(() -> health(endpoint).get("disabled_notified_at") != null);
        var email = emails.to(owner).getFirst();
        assertThat(email.subject()).isEqualTo("We turned off a webhook endpoint for Shop " + shop);
        assertThat(email.text())
                .contains(
                        PARTNER.baseUrl() + path(endpoint), "Last error: HTTP 503", "/b/" + shop + "/settings?tab=api");
        assertThat(emails.to(technician)).isEmpty();
        Thread.sleep(500);
        assertThat(emails.to(owner)).hasSize(1); // told once

        var next = Events.id();
        publish("payments.refund", next, "payments.refund_issued", refundIssued(next, shop));
        Thread.sleep(1_000);
        assertThat(state(endpoint, next)).isNull(); // a turned-off endpoint gets nothing new
    }

    @Test
    void aSlowEndpointHoldsUpOnlyItself() {
        var slow = endpoint(merchant(), List.of("booking.completed"));
        var fast = endpoint(merchant(), List.of("booking.completed"));
        PARTNER.stubFor(post(path(slow)).willReturn(aResponse().withStatus(200).withFixedDelay(2_000)));
        ok(fast);
        var slowOnes =
                List.of(queue(slow, Events.id(), T0), queue(slow, Events.id(), T0), queue(slow, Events.id(), T0));
        var fastOne = queue(fast, Events.id(), T0);

        await().atMost(Duration.ofMillis(1_500)).until(() -> "succeeded".equals(stateOf(fastOne)));

        assertThat(slowOnes.stream().filter(d -> "succeeded".equals(stateOf(d))).count())
                .isLessThanOrEqualTo(1);
        await().atMost(Duration.ofSeconds(15))
                .until(() -> slowOnes.stream().allMatch(d -> "succeeded".equals(stateOf(d))));
    }

    @Test
    void refusedDestinationsAreFailedAttempts_andNothingIsSent() {
        var shop = merchant();
        var metadata = endpoint(shop, "https://169.254.169.254/latest/meta-data/", List.of("booking.completed"));
        var privateNet = endpoint(shop, "http://10.0.0.8/hooks", List.of("booking.completed"));
        var credentials = endpoint(shop, "https://user:pw@partner.example/hooks", List.of("booking.completed"));
        var a = queue(metadata, shop, T0);
        var b = queue(privateNet, shop, T0);
        var c = queue(credentials, shop, T0);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> List.of(a, b, c).stream().allMatch(d -> attemptsOf(d) == 1));

        assertThat(errorOf(a))
                .isEqualTo("refused: 169.254.169.254 resolves to 169.254.169.254 (link-local / cloud metadata)");
        assertThat(errorOf(b)).isEqualTo("refused: 10.0.0.8 resolves to 10.0.0.8 (private network)");
        assertThat(errorOf(c)).isEqualTo("refused: credentials in the URL are not allowed");
        assertThat(stateOf(a))
                .isEqualTo("pending"); // retried like any failure (DNS may change); counts toward auto-disable
    }

    @Test
    void sendTestEvent_thenResend_sameEventIdNewDelivery() throws Exception {
        var shop = merchant();
        var endpoint = endpoint(shop, List.of("booking.completed"));
        ok(endpoint);
        var test = testDelivery(endpoint, shop);

        await().atMost(Duration.ofSeconds(15)).until(() -> "succeeded".equals(stateOf(test)));
        var sent = requests(endpoint).getFirst();
        var body = JSON.readTree(sent.getBodyAsString());
        assertThat(body.path("type").asString()).isEqualTo("webhook.test");
        assertThat(body.path("data").path("endpointId").asString()).isEqualTo(endpoint);
        assertThat(map(jdbc.sql("select payload from developer.webhook_deliveries where id = ?")
                        .params(test)
                        .query(String.class)
                        .single()))
                .isEqualTo(map(sent.getBodyAsString()));

        var resend = Events.id();
        jdbc.sql("""
                        insert into developer.webhook_deliveries
                               (id, endpoint_id, merchant_id, event_id, event_type, payload, state, attempt,
                                next_attempt_at, resend_of, test, created_at)
                        select ?, endpoint_id, merchant_id, event_id, event_type, payload, 'pending', 0, ?, id, test, ?
                          from developer.webhook_deliveries where id = ?""").params(resend, ts(T0), ts(T0), test).update();

        await().atMost(Duration.ofSeconds(15)).until(() -> "succeeded".equals(stateOf(resend)));
        var both = requests(endpoint);
        assertThat(both).hasSize(2);
        assertThat(both.get(1).getHeader(WebhookDispatcher.EVENT_ID))
                .isEqualTo(sent.getHeader(WebhookDispatcher.EVENT_ID));
        assertThat(both.get(1).getHeader(WebhookDispatcher.DELIVERY_ID)).isEqualTo(resend);
        assertThat(both.get(1).getBodyAsString()).isEqualTo(sent.getBodyAsString());
    }

    @Test
    void duringARotationBothSecretsSign_afterTheOverlapOnlyTheNewOne() {
        var shop = merchant();
        var endpoint = endpoint(shop, List.of("booking.completed"));
        ok(endpoint);
        jdbc.sql("update developer.webhook_endpoints set secret_prev_enc = ?, secret_prev_until = ? where id = ?")
                .params(BOX.encrypt(OLD_SECRET), ts(T0.plus(Duration.ofHours(24))), endpoint)
                .update();
        var first = testDelivery(endpoint, shop);
        await().atMost(Duration.ofSeconds(15)).until(() -> "succeeded".equals(stateOf(first)));

        var overlap = requests(endpoint).getFirst();
        assertThat(overlap.getHeader(WebhookSigner.HEADER).split(",v1=")).hasSize(3);
        assertThat(verify(overlap, SECRET)).isTrue();
        assertThat(verify(overlap, OLD_SECRET)).isTrue();

        clock.set(T0.plus(Duration.ofHours(25)));
        var second = testDelivery(endpoint, shop);
        await().atMost(Duration.ofSeconds(15)).until(() -> "succeeded".equals(stateOf(second)));

        var after = requests(endpoint).get(1);
        assertThat(after.getHeader(WebhookSigner.HEADER).split(",v1=")).hasSize(2);
        assertThat(verify(after, SECRET)).isTrue();
        assertThat(verify(after, OLD_SECRET)).isFalse();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────────────────────

    String merchant() {
        var id = Events.id(); // ULID-shaped, as the public payload's schema requires
        jdbc.sql("insert into merchants.merchants (id, display_name, legal_name) values (?, ?, ?)")
                .params(id, "Shop " + id, "Shop " + id)
                .update();
        return id;
    }

    /** A team member; returns their email address. */
    String member(String merchantId, String role) {
        var id = "u_" + Events.id();
        var email = id.toLowerCase(java.util.Locale.ROOT) + "@example.com";
        jdbc.sql("""
                        insert into identity.users (id, email, first_name, last_name, display_name, locale, status)
                        values (?, ?, 'Sam', 'Lee', 'Sam Lee', 'en-CA', 'active')""").params(id, email).update();
        jdbc.sql("insert into merchants.merchant_members (merchant_id, user_id, role) values (?, ?, ?)")
                .params(merchantId, id, role)
                .update();
        return email;
    }

    String endpoint(String merchantId, List<String> events) {
        var id = "wh_" + Events.id();
        return endpoint(id, merchantId, PARTNER.baseUrl() + path(id), events);
    }

    String endpoint(String merchantId, String url, List<String> events) {
        return endpoint("wh_" + Events.id(), merchantId, url, events);
    }

    String endpoint(String id, String merchantId, String url, List<String> events) {
        jdbc.sql("""
                        insert into developer.webhook_endpoints
                               (id, merchant_id, url, secret_ref, secret_enc, events, active, created_by, created_at)
                        values (?, ?, ?, 'db:aes-gcm:v1', ?, cast(? as text[]), true, 'u_owner', now())""")
                .params(id, merchantId, url, BOX.encrypt(SECRET), "{" + String.join(",", events) + "}")
                .update();
        return id;
    }

    /** A delivery queued directly (as the fan-out or the api would), due at {@code at}. */
    String queue(String endpointId, String merchantId, Instant at) {
        var id = Events.id();
        var eventId = Events.id();
        jdbc.sql("""
                        insert into developer.webhook_deliveries
                               (id, endpoint_id, merchant_id, event_id, event_type, payload, state, attempt,
                                next_attempt_at, created_at)
                        values (?, ?, ?, ?, 'booking.completed', ?, 'pending', 0, ?, ?)""")
                .params(id, endpointId, merchantId, eventId, """
                        {"id":"%s","type":"booking.completed","version":1,"createdAt":"2026-09-30T15:00:00Z",
                         "merchantId":"%s","data":{"bookingId":"bk_1","completedBy":"u_1","photoCount":0}}""".formatted(eventId, merchantId), ts(at), ts(at))
                .update();
        return id;
    }

    /** "Send test event" as the api queues it: no payload yet, the worker writes it. */
    String testDelivery(String endpointId, String merchantId) {
        var id = Events.id();
        jdbc.sql("""
                        insert into developer.webhook_deliveries
                               (id, endpoint_id, merchant_id, event_id, event_type, state, attempt, next_attempt_at,
                                test, created_at)
                        values (?, ?, ?, ?, 'webhook.test', 'pending', 0, ?, true, ?)""")
                .params(id, endpointId, merchantId, Events.id(), ts(clock.instant()), ts(clock.instant()))
                .update();
        return id;
    }

    void ok(String... endpoints) {
        for (var endpoint : endpoints) {
            PARTNER.stubFor(
                    post(path(endpoint)).willReturn(aResponse().withStatus(200).withBody("thanks")));
        }
    }

    static String path(String endpointId) {
        return "/hooks/" + endpointId;
    }

    static List<LoggedRequest> requests(String endpointId) {
        return PARTNER.findAll(postRequestedFor(urlEqualTo(path(endpointId))));
    }

    static boolean verify(LoggedRequest request, String secret) {
        return WebhookSigner.verify(
                request.getHeader(WebhookSigner.HEADER),
                request.getBody(),
                secret,
                Instant.ofEpochSecond(Long.parseLong(request.getHeader(WebhookSigner.HEADER)
                        .substring(2, request.getHeader(WebhookSigner.HEADER).indexOf(',')))),
                WebhookSigner.TOLERANCE);
    }

    @Nullable
    String state(String endpointId, String eventId) {
        return jdbc.sql("select state from developer.webhook_deliveries where endpoint_id = ? and event_id = ?")
                .params(endpointId, eventId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    String deliveryId(String endpointId, String eventId) {
        return jdbc.sql("select id from developer.webhook_deliveries where endpoint_id = ? and event_id = ?")
                .params(endpointId, eventId)
                .query(String.class)
                .single();
    }

    int attempts(String endpointId, String eventId) {
        return jdbc.sql(
                        "select coalesce(max(attempt), 0) from developer.webhook_deliveries where endpoint_id = ? and event_id = ?")
                .params(endpointId, eventId)
                .query(Integer.class)
                .single();
    }

    Map<String, Object> delivery(String endpointId, String eventId) {
        return jdbc.sql("""
                        select state, attempt, status_code, response_snippet, next_attempt_at
                          from developer.webhook_deliveries where endpoint_id = ? and event_id = ?""").params(endpointId, eventId).query().singleRow();
    }

    @Nullable
    String stateOf(String deliveryId) {
        return jdbc.sql("select state from developer.webhook_deliveries where id = ?")
                .params(deliveryId)
                .query(String.class)
                .single();
    }

    int attemptsOf(String deliveryId) {
        return jdbc.sql("select attempt from developer.webhook_deliveries where id = ?")
                .params(deliveryId)
                .query(Integer.class)
                .single();
    }

    @Nullable
    String errorOf(String deliveryId) {
        return jdbc.sql("select error from developer.webhook_deliveries where id = ?")
                .params(deliveryId)
                .query(String.class)
                .single();
    }

    Map<String, Object> health(String endpointId) {
        return jdbc.sql("""
                        select active, consecutive_failures, disabled_reason, disabled_notified_at
                          from developer.webhook_endpoints where id = ?""").params(endpointId).query().singleRow();
    }

    double outcome(String type, String outcome) {
        var counter = meters.find(EventProcessing.CONSUMED)
                .tags("consumer", "webhooks", "type", type, "outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    double deadLettered(String topic) {
        var counter = meters.find(EventProcessing.DEAD_LETTERED)
                .tags("consumer", "webhooks", "topic", topic)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    double duplicates(String type) {
        var counter = meters.find(EventProcessing.CONSUMED)
                .tags("consumer", "webhooks", "type", type, "outcome", "duplicate")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    /** JSON as a map (JsonNode is an Iterable, which AssertJ compares in order). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(String json) {
        return JSON.readValue(json, Map.class);
    }

    static java.sql.Timestamp ts(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }

    static void publish(String topic, String id, String type, String json) throws Exception {
        producer.send(Events.record(topic, id, type, 1, json)).get();
    }

    static String bookingCompleted(String id, String merchant) {
        return """
                {"eventId":"%s","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"bk_%s","merchantId":"%s",
                 "actorId":"u_tech","photoCount":2}""".formatted(id, id, merchant);
    }

    static String escrowReleased(String id, String merchant) {
        return """
                {"eventId":"%s","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"esc_%s","merchantId":"%s",
                 "refType":"booking","refId":"bk_1","grossCents":38900,"feeCents":3890,"netCents":35010}""".formatted(id, id, merchant);
    }

    static String refundIssued(String id, String merchant) {
        return """
                {"eventId":"%s","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"rf_%s","merchantId":"%s",
                 "caseNumber":"RF-2214","escrowId":null,"amountCents":4500,"chargedTo":"merchant"}""".formatted(id, id, merchant);
    }
}
