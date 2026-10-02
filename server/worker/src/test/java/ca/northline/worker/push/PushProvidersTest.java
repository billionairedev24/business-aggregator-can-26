package ca.northline.worker.push;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.worker.notifications.PushApp;
import ca.northline.worker.notifications.PushDeliveryFailed;
import ca.northline.worker.notifications.PushSender;
import ca.northline.worker.support.Events;
import ca.northline.worker.support.WorkerContainers;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-102: the APNs and FCM adapters against WireMock stand-ins of Apple's and Google's documented APIs, with the device
 * registry in PostgreSQL. Keys are generated per run (fake; nothing here is a real credential). Never run against
 * Apple or Google.
 */
class PushProvidersTest {

    static final WireMockServer APPLE = new WireMockServer(wireMockConfig().dynamicPort());
    static final WireMockServer GOOGLE = new WireMockServer(wireMockConfig().dynamicPort());
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final KeyPair APNS_KEY = keys("EC");
    static final KeyPair FCM_KEY = keys("RSA");
    static JdbcClient jdbc;

    final TestClock clock = new TestClock(Instant.parse("2026-10-02T18:00:00Z"));
    PushProperties props;
    DevicePushSender sender;

    @BeforeAll
    static void start() {
        WorkerContainers.start();
        var pg = WorkerContainers.POSTGRES;
        jdbc = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        APPLE.start();
        GOOGLE.start();
    }

    @AfterAll
    static void stop() {
        APPLE.stop();
        GOOGLE.stop();
    }

    @BeforeEach
    void wire() {
        APPLE.resetAll();
        GOOGLE.resetAll();
        GOOGLE.stubFor(post(urlEqualTo("/token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"ya29.fake-access-token\",\"expires_in\":3599,"
                                + "\"token_type\":\"Bearer\"}")));
        props = new PushProperties(
                PushProperties.Provider.NATIVE,
                new PushProperties.Apns(
                        APPLE.baseUrl(),
                        "ABC123DEFG",
                        "TEAM123456",
                        pem(APNS_KEY),
                        "ca.northline.app",
                        "ca.northline.courier"),
                new PushProperties.Fcm(GOOGLE.baseUrl(), serviceAccount(), null),
                Duration.ofDays(90),
                Duration.ofSeconds(30),
                Duration.ofMinutes(15),
                Duration.ofSeconds(5));
        var apns = new ApnsPushProvider(
                PushConfiguration.client(
                        APPLE.baseUrl(), props, java.net.http.HttpClient.Version.HTTP_2, ApnsApi.class),
                "ABC123DEFG",
                "TEAM123456",
                pem(APNS_KEY),
                "ca.northline.app",
                "ca.northline.courier",
                JSON,
                clock);
        var fcm = new FcmPushProvider(
                PushConfiguration.client(
                        GOOGLE.baseUrl(), props, java.net.http.HttpClient.Version.HTTP_1_1, FcmApi.class),
                serviceAccount(),
                null,
                JSON,
                clock);
        sender = new DevicePushSender(
                new PushDeviceStore(jdbc), java.util.List.of(apns, fcm), props, clock, new SimpleMeterRegistry());
    }

    @Test
    void apns_getsAnHttp2RequestWithAProviderToken_theTopic_aCollapseId_andOnlyWordsLinkAndIds() throws Exception {
        var user = "u_" + Events.id();
        var token = device(user, "consumer", "ios", "fr-CA");
        APPLE.stubFor(
                post(urlPathMatching("/3/device/.*")).willReturn(aResponse().withStatus(200)));

        assertThat(sender.send(message(user, PushApp.CONSUMER, null))).isEqualTo(PushSender.Result.DELIVERED);

        var request = APPLE.findAll(postRequestedFor(urlEqualTo("/3/device/" + token)))
                .getFirst();
        assertThat(request.getHeader("apns-topic")).isEqualTo("ca.northline.app");
        assertThat(request.getHeader("apns-push-type")).isEqualTo("alert");
        assertThat(request.getHeader("apns-collapse-id")).isEqualTo("order:01J9ZD3V00000000000000ORD1");
        var jwt = request.getHeader("authorization").substring("bearer ".length());
        var header = part(jwt, 0);
        assertThat(header.path("alg").asString()).isEqualTo("ES256");
        assertThat(header.path("kid").asString()).isEqualTo("ABC123DEFG");
        assertThat(part(jwt, 1).path("iss").asString()).isEqualTo("TEAM123456");
        assertThat(verifies(jwt, APNS_KEY.getPublic(), "SHA256withECDSAinP1363Format"))
                .isTrue();
        var payload = JSON.readTree(request.getBodyAsString());
        assertThat(payload.path("aps").path("alert").path("title").asString()).isEqualTo("En livraison"); // device: fr
        assertThat(payload.path("link").asString())
                .isEqualTo("https://northline.test/orders/01J9ZD3V00000000000000ORD1");
        assertThat(payload.path("data").path("orderId").asString()).isEqualTo("01J9ZD3V00000000000000ORD1");
        assertThat(fieldNames(payload)).containsExactlyInAnyOrder("aps", "link", "data");
        assertThat(fieldNames(payload.path("data"))).containsExactlyInAnyOrder("type", "orderId");
    }

    @Test
    void apns_unregisteredOrBadToken_deletesTheDevice_andTheCourierAppUsesItsTopic() {
        var user = "u_" + Events.id();
        var gone = device(user, "courier", "ios", "en-CA");
        var bad = device(user, "courier", "ios", "en-CA");
        APPLE.stubFor(post(urlEqualTo("/3/device/" + gone))
                .willReturn(aResponse()
                        .withStatus(410)
                        .withBody("{\"reason\":\"Unregistered\",\"timestamp\":1727900000000}")));
        APPLE.stubFor(post(urlEqualTo("/3/device/" + bad))
                .willReturn(aResponse().withStatus(400).withBody("{\"reason\":\"BadDeviceToken\"}")));

        assertThat(sender.send(message(user, PushApp.COURIER, "en"))).isEqualTo(PushSender.Result.NO_DEVICE);

        assertThat(devices(user)).isZero();
        APPLE.verify(
                2,
                postRequestedFor(urlPathMatching("/3/device/.*"))
                        .withHeader("apns-topic", equalTo("ca.northline.courier")));
    }

    @Test
    void apns_anExpiredProviderToken_isRenewedAndSentAgain() {
        var user = "u_" + Events.id();
        device(user, "consumer", "ios", "en-CA");
        APPLE.stubFor(post(urlPathMatching("/3/device/.*"))
                .inScenario("jwt")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(403).withBody("{\"reason\":\"ExpiredProviderToken\"}"))
                .willSetStateTo("renewed"));
        APPLE.stubFor(post(urlPathMatching("/3/device/.*"))
                .inScenario("jwt")
                .whenScenarioStateIs("renewed")
                .willReturn(aResponse().withStatus(200)));

        assertThat(sender.send(message(user, PushApp.CONSUMER, "en"))).isEqualTo(PushSender.Result.DELIVERED);
        APPLE.verify(2, postRequestedFor(urlPathMatching("/3/device/.*")));
    }

    @Test
    void apns_throttling_pausesTheProvider_untilTheBackOffEnds() {
        var user = "u_" + Events.id();
        device(user, "consumer", "ios", "en-CA");
        APPLE.stubFor(post(urlPathMatching("/3/device/.*"))
                .inScenario("busy")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withBody("{\"reason\":\"TooManyRequests\"}"))
                .willSetStateTo("calm"));
        APPLE.stubFor(post(urlPathMatching("/3/device/.*"))
                .inScenario("busy")
                .whenScenarioStateIs("calm")
                .willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> sender.send(message(user, PushApp.CONSUMER, "en")))
                .isInstanceOf(PushDeliveryFailed.class)
                .satisfies(
                        e -> assertThat(((PushDeliveryFailed) e).retryAfter()).isEqualTo(Duration.ofSeconds(30)));
        // still paused: APNs isn't called again
        clock.advance(Duration.ofSeconds(10));
        assertThatThrownBy(() -> sender.send(message(user, PushApp.CONSUMER, "en")))
                .isInstanceOf(PushDeliveryFailed.class)
                .hasMessageContaining("paused");
        APPLE.verify(1, postRequestedFor(urlPathMatching("/3/device/.*")));

        clock.advance(Duration.ofSeconds(25));
        assertThat(sender.send(message(user, PushApp.CONSUMER, "en"))).isEqualTo(PushSender.Result.DELIVERED);
        APPLE.verify(2, postRequestedFor(urlPathMatching("/3/device/.*")));
        assertThat(devices(user)).isEqualTo(1); // throttling never deletes a device
    }

    @Test
    void fcm_exchangesASignedAssertionForAnAccessToken_andSendsTheV1Message() throws Exception {
        var user = "u_" + Events.id();
        var token = device(user, "consumer", "android", "en-CA");
        GOOGLE.stubFor(post(urlEqualTo("/v1/projects/northline-test/messages:send"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"projects/northline-test/messages/0:1\"}")));

        assertThat(sender.send(message(user, PushApp.CONSUMER, null))).isEqualTo(PushSender.Result.DELIVERED);
        assertThat(sender.send(message(user, PushApp.CONSUMER, null))).isEqualTo(PushSender.Result.DELIVERED);

        GOOGLE.verify(1, postRequestedFor(urlEqualTo("/token"))); // the access token is reused
        var form = GOOGLE.findAll(postRequestedFor(urlEqualTo("/token")))
                .getFirst()
                .getBodyAsString();
        assertThat(form).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer");
        var assertion = URLDecoder.decode(form.replaceAll(".*assertion=([^&]+).*", "$1"), StandardCharsets.UTF_8);
        assertThat(part(assertion, 1).path("scope").asString())
                .isEqualTo("https://www.googleapis.com/auth/firebase.messaging");
        assertThat(part(assertion, 1).path("iss").asString()).isEqualTo("push@northline-test.iam.gserviceaccount.com");
        assertThat(verifies(assertion, FCM_KEY.getPublic(), "SHA256withRSA")).isTrue();

        var send = GOOGLE.findAll(postRequestedFor(urlEqualTo("/v1/projects/northline-test/messages:send")))
                .getFirst();
        assertThat(send.getHeader("Authorization")).isEqualTo("Bearer ya29.fake-access-token");
        var message = JSON.readTree(send.getBodyAsString()).path("message");
        assertThat(message.path("token").asString()).isEqualTo(token);
        assertThat(message.path("notification").path("title").asString()).isEqualTo("Out for delivery");
        assertThat(message.path("data").path("link").asString())
                .isEqualTo("https://northline.test/orders/01J9ZD3V00000000000000ORD1");
        assertThat(message.path("android").path("collapse_key").asString())
                .isEqualTo("order:01J9ZD3V00000000000000ORD1");
        assertThat(fieldNames(message.path("data"))).containsExactlyInAnyOrder("type", "orderId", "link");
    }

    @Test
    void fcm_unregistered_deletesTheDevice() {
        var user = "u_" + Events.id();
        device(user, "consumer", "android", "en-CA");
        GOOGLE.stubFor(post(urlEqualTo("/v1/projects/northline-test/messages:send"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"error":{"code":404,"message":"Requested entity was not found.","status":"NOT_FOUND",
                                "details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",
                                "errorCode":"UNREGISTERED"}]}}""")));

        assertThat(sender.send(message(user, PushApp.CONSUMER, "en"))).isEqualTo(PushSender.Result.NO_DEVICE);
        assertThat(devices(user)).isZero();
    }

    @Test
    void fcm_quotaExceeded_honoursRetryAfter_andAnotherPlatformStillDelivers() {
        var user = "u_" + Events.id();
        device(user, "consumer", "android", "en-CA");
        GOOGLE.stubFor(post(urlEqualTo("/v1/projects/northline-test/messages:send"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "120")
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded",
                                "details":[{"errorCode":"QUOTA_EXCEEDED"}]}}""")));

        assertThatThrownBy(() -> sender.send(message(user, PushApp.CONSUMER, "en")))
                .isInstanceOf(PushDeliveryFailed.class)
                .satisfies(
                        e -> assertThat(((PushDeliveryFailed) e).retryAfter()).isEqualTo(Duration.ofMinutes(2)));

        // the same person's iPhone still gets it: delivered to one installation = sent
        device(user, "consumer", "ios", "en-CA");
        APPLE.stubFor(
                post(urlPathMatching("/3/device/.*")).willReturn(aResponse().withStatus(200)));
        assertThat(sender.send(message(user, PushApp.CONSUMER, "en"))).isEqualTo(PushSender.Result.DELIVERED);
        GOOGLE.verify(1, postRequestedFor(urlEqualTo("/v1/projects/northline-test/messages:send")));
    }

    @Test
    void devicesThatDeniedNotificationsOrWentStale_getNothing() {
        var user = "u_" + Events.id();
        var denied = device(user, "consumer", "ios", "en-CA");
        jdbc.sql("update messaging.push_devices set permission = 'denied' where token = :t")
                .param("t", denied)
                .update();
        var stale = device(user, "consumer", "ios", "en-CA");
        jdbc.sql("update messaging.push_devices set refreshed_at = now() - interval '200 days' where token = :t")
                .param("t", stale)
                .update();
        device(user, "courier", "ios", "en-CA"); // another app

        assertThat(sender.send(message(user, PushApp.CONSUMER, "en"))).isEqualTo(PushSender.Result.NO_DEVICE);
        APPLE.verify(0, postRequestedFor(urlPathMatching("/3/device/.*")));
        assertThat(new PushDeviceStore(jdbc).prune(Instant.now().minus(Duration.ofDays(90))))
                .isGreaterThanOrEqualTo(1);
        assertThat(devices(user)).isEqualTo(2);
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    static PushSender.PushMessage message(String user, PushApp app, @Nullable String language) {
        return new PushSender.PushMessage(
                user,
                app,
                Map.of(
                        "en", new PushSender.Content("Out for delivery", "Your order is out for delivery."),
                        "fr", new PushSender.Content("En livraison", "Votre commande est en livraison.")),
                language,
                URI.create("https://northline.test/orders/01J9ZD3V00000000000000ORD1"),
                Map.of("type", "order.out_for_delivery", "orderId", "01J9ZD3V00000000000000ORD1"),
                "order:01J9ZD3V00000000000000ORD1");
    }

    static String device(String user, String app, String platform, String locale) {
        return device(jdbc, user, app, platform, locale);
    }

    static String device(JdbcClient jdbc, String user, String app, String platform, String locale) {
        var token = ("ios".equals(platform) ? "a1b2c3d4e5f6" : "fcm:APA91b-")
                + Events.id().toLowerCase(java.util.Locale.ROOT);
        jdbc.sql("""
                        insert into messaging.push_devices (id, user_id, app, installation_id, platform, token, locale,
                               app_version, permission)
                        values (:id, :user, :app, :installation, :platform, :token, :locale, '1.0.0', 'granted')""")
                .param("id", Events.id())
                .param("user", user)
                .param("app", app)
                .param("installation", "inst" + Events.id())
                .param("platform", platform)
                .param("token", token)
                .param("locale", locale)
                .update();
        return token;
    }

    static int devices(String user) {
        return jdbc.sql("select count(*) from messaging.push_devices where user_id = :u")
                .param("u", user)
                .query(Integer.class)
                .single();
    }

    static KeyPair keys(String algorithm) {
        try {
            var generator = KeyPairGenerator.getInstance(algorithm);
            if (algorithm.equals("EC")) {
                generator.initialize(new ECGenParameterSpec("secp256r1"));
            } else {
                generator.initialize(2048);
            }
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String pem(KeyPair keys) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    /** A Firebase service account's JSON key, shaped like Google's (fake project, fake key). */
    static String serviceAccount() {
        return JSON.writeValueAsString(Map.of(
                "type", "service_account",
                "project_id", "northline-test",
                "private_key_id", "0123456789abcdef",
                "private_key", pem(FCM_KEY),
                "client_email", "push@northline-test.iam.gserviceaccount.com",
                "token_uri", GOOGLE.baseUrl() + "/token"));
    }

    static JsonNode part(String jwt, int index) {
        return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[index]));
    }

    static boolean verifies(String jwt, PublicKey key, String algorithm) throws Exception {
        var parts = jwt.split("\\.");
        var verifier = Signature.getInstance(algorithm);
        verifier.initVerify(key);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        return verifier.verify(Base64.getUrlDecoder().decode(parts[2]));
    }

    static java.util.List<String> fieldNames(JsonNode node) {
        var names = new java.util.ArrayList<String>();
        for (var name : node.propertyNames()) {
            names.add(name);
        }
        return names;
    }

    static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
