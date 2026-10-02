package ca.northline.worker.push;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.northline.worker.events.EventEnvelope;
import ca.northline.worker.notifications.Notifier;
import ca.northline.worker.notifications.PushSender;
import ca.northline.worker.support.Events;
import ca.northline.worker.support.WorkerContainers;
import ca.northline.worker.support.WorkerIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-102 with the real adapters ({@code northline.push.provider=native}) against WireMock stand-ins of APNs and FCM: an
 * event about a customer's order (handed to the notifier as its consumer does) reaches their iPhone through APNs with the words, a deep link and ids — never their
 * name, email or phone — and the Android installation FCM says is gone leaves the registry.
 */
@DirtiesContext
class PushEndToEndTest extends WorkerIntegrationTest {

    static final WireMockServer APPLE = new WireMockServer(wireMockConfig().dynamicPort());
    static final WireMockServer GOOGLE = new WireMockServer(wireMockConfig().dynamicPort());
    static final KeyPair APNS_KEY = PushProvidersTest.keys("EC");
    static final KeyPair FCM_KEY = PushProvidersTest.keys("RSA");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PushSender sender;

    @Autowired
    Notifier notifier;

    @DynamicPropertySource
    static void push(DynamicPropertyRegistry registry) {
        APPLE.start();
        GOOGLE.start();
        registry.add("northline.push.provider", () -> "native");
        registry.add("northline.push.apns.url", APPLE::baseUrl);
        registry.add("northline.push.apns.key-id", () -> "ABC123DEFG");
        registry.add("northline.push.apns.team-id", () -> "TEAM123456");
        registry.add("northline.push.apns.key", () -> PushProvidersTest.pem(APNS_KEY));
        registry.add("northline.push.fcm.url", GOOGLE::baseUrl);
        registry.add(
                "northline.push.fcm.service-account",
                () -> JsonMapper.builder()
                        .build()
                        .writeValueAsString(Map.of(
                                "project_id",
                                "northline-test",
                                "private_key",
                                PushProvidersTest.pem(FCM_KEY),
                                "client_email",
                                "push@northline-test.iam.gserviceaccount.com",
                                "token_uri",
                                GOOGLE.baseUrl() + "/token")));
        registry.add("northline.notifications.consumer-url", () -> "https://northline.test");
        // This context's Kafka listeners stay off: the other worker tests' context shares the consumer groups (a
        // second member would take half their partitions). The notifier is called as the consumer calls it.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @BeforeAll
    static void stubs() {
        WorkerContainers.start();
        APPLE.stubFor(
                post(urlPathMatching("/3/device/.*")).willReturn(aResponse().withStatus(200)));
        GOOGLE.stubFor(post(urlEqualTo("/token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"ya29.fake\",\"expires_in\":3599}")));
        GOOGLE.stubFor(post(urlEqualTo("/v1/projects/northline-test/messages:send"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\",\"message\":\"Requested entity"
                                + " was not found.\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}")));
    }

    @AfterAll
    static void stop() {
        APPLE.stop();
        GOOGLE.stop();
    }

    @Test
    void theRealAdaptersAreWired() {
        assertThat(sender).isInstanceOf(DevicePushSender.class);
    }

    @Test
    void outForDelivery_reachesTheIphone_withoutPersonalData_andTheDeadAndroidTokenIsRemoved() throws Exception {
        var user = "u_" + Events.id();
        var email = user.toLowerCase(Locale.ROOT) + "@example.com";
        var phone = "+1587557" + String.format("%04d", System.nanoTime() / 1000 % 10_000);
        jdbc.sql("""
                        insert into identity.users (id, email, phone, first_name, last_name, display_name, locale, status)
                        values (:id, :email, :phone, 'Amara', 'Osei', 'Amara Osei', 'en-CA', 'active')""")
                .param("id", user)
                .param("email", email)
                .param("phone", phone)
                .update();
        var order = Events.id();
        jdbc.sql("""
                        insert into orders.orders (id, customer_id, type, state, fulfilment_mode)
                        values (:id, :c, 'goods', 'picked_up', 'delivery')""").param("id", order).param("c", user).update();
        var iphone = PushProvidersTest.device(jdbc, user, "consumer", "ios", "en-CA");
        var android = PushProvidersTest.device(jdbc, user, "consumer", "android", "fr-CA");
        var id = Events.id();

        var json = """
                {"eventId":"%s","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"%s","runId":"run_1",\
                "courierId":"co_1"}""".formatted(id, order);
        notifier.on(new EventEnvelope(
                id,
                "fulfilment.delivery_picked_up",
                1,
                Instant.parse("2026-09-30T15:00:00Z"),
                order,
                null,
                "fulfilment.delivery",
                JsonMapper.builder().build().readTree(json)));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> APPLE.verify(1, postRequestedFor(urlEqualTo("/3/device/" + iphone))));
        await().atMost(Duration.ofSeconds(30))
                .until(() -> jdbc.sql("select count(*) from messaging.push_devices where token = :t")
                                .param("t", android)
                                .query(Integer.class)
                                .single()
                        == 0);
        var apns = APPLE.findAll(postRequestedFor(urlEqualTo("/3/device/" + iphone)))
                .getFirst()
                .getBodyAsString();
        var fcm = GOOGLE.findAll(postRequestedFor(urlEqualTo("/v1/projects/northline-test/messages:send")))
                .getFirst()
                .getBodyAsString();
        for (var body : java.util.List.of(apns, fcm)) {
            assertThat(body)
                    .contains(order, "https://northline.test/orders/" + order)
                    .doesNotContain(email, phone, "Amara", "Osei", user);
        }
        assertThat(apns).contains("Out for delivery");
        assertThat(fcm).contains("En livraison"); // that phone is in French
    }
}
