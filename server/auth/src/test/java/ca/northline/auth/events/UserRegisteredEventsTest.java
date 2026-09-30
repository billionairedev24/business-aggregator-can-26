package ca.northline.auth.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.application.SignInLog;
import ca.northline.auth.support.AuthIntegrationTest;
import ca.northline.platform.EventHeaders;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * S-28: {@code user.registered} is written to northline-auth's outbox ({@code auth.event_publication}) in the
 * registration transaction and externalized to a real Kafka 4 (Testcontainers, the compose image) on topic
 * {@code identity.user}, key = user id, ids only, with the {@code nl-event-*} envelope headers — exactly once per
 * registration; a registration that rolls back leaves
 * neither an account, nor a publication, nor a record.
 */
class UserRegisteredEventsTest extends AuthIntegrationTest {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false"); // like every environment: topics are provisioned
    static final String TOPIC = "identity.user";
    static final String FAILING_CLIENT = "northline-s28-rollback-test";

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.modulith.events.externalization.enabled", () -> "true");
    }

    @BeforeAll
    static void topic() throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            if (!admin.listTopics().names().get().contains(TOPIC)) {
                admin.createTopics(List.of(new NewTopic(TOPIC, 6, (short) 1)))
                        .all()
                        .get();
            }
        }
    }

    /** The sign-in log fails for one test's registration, after the account row and the event: the transaction rolls back. */
    @MockitoSpyBean
    SignInLog signIns;

    private static List<ConsumerRecord<String, String>> recordsFor(String userId, Duration within) {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "s28-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var found = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(TOPIC));
            var end = System.nanoTime() + within.toNanos();
            while (System.nanoTime() < end) {
                consumer.poll(Duration.ofMillis(250)).forEach(r -> {
                    if (userId.equals(r.key())) {
                        found.add(r);
                    }
                });
            }
        }
        return found;
    }

    private long publications(String userId) {
        return jdbc.sql("""
                        SELECT (SELECT count(*) FROM auth.event_publication
                                 WHERE event_type LIKE '%UserRegistered' AND serialized_event LIKE :id)
                             + (SELECT count(*) FROM auth.event_publication_archive
                                 WHERE event_type LIKE '%UserRegistered' AND serialized_event LIKE :id)
                        """).param("id", "%" + userId + "%").query(Long.class).single();
    }

    @Test
    void aRegistrationIsPublishedExactlyOnce_toIdentityUser_keyedByUserId_withIdsOnly() throws Exception {
        var person = newPerson();
        var user = register(person);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbc.sql("""
                                SELECT count(*) FROM auth.event_publication_archive
                                 WHERE serialized_event LIKE :id AND completion_date IS NOT NULL
                                """)
                                .param("id", "%" + user.userId() + "%")
                                .query(Long.class)
                                .single())
                        .isEqualTo(1));
        assertThat(publications(user.userId())).isEqualTo(1);

        var records = recordsFor(user.userId(), Duration.ofSeconds(5));
        assertThat(records).hasSize(1);
        var json = records.getFirst().value();
        assertThat(JsonPath.<String>read(json, "$.aggregateId")).isEqualTo(user.userId());
        assertThat(JsonPath.<String>read(json, "$.eventId")).matches("[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(JsonPath.<String>read(json, "$.occurredAt")).isNotBlank();
        assertThat(JsonPath.<Map<String, Object>>read(json, "$"))
                .containsOnlyKeys("eventId", "occurredAt", "aggregateId");
        assertThat(json).doesNotContain(person.email(), person.e164(), person.firstName(), person.lastName());
        // The envelope headers the worker's consumers require (S-26), as the api sets them.
        var record = records.getFirst();
        assertThat(header(record, EventHeaders.ID)).isEqualTo(JsonPath.<String>read(json, "$.eventId"));
        assertThat(header(record, EventHeaders.TYPE)).isEqualTo("identity.user_registered");
        assertThat(header(record, EventHeaders.VERSION)).isEqualTo("1");
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        assertThat(header).as(name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    void aRegistrationThatRollsBack_publishesNothing() throws Exception {
        var failedUser = new AtomicReference<String>();
        doAnswer(call -> {
                    failedUser.set(call.getArgument(0));
                    throw new IllegalStateException("simulated failure after the account and the event");
                })
                .when(signIns)
                .succeeded(
                        anyString(),
                        eq("registration"),
                        anyBoolean(),
                        argThat(c -> c != null && FAILING_CLIENT.equals(c.userAgent())));

        var person = newPerson();
        var session = new MockHttpSession();
        mvc.perform(post("/api/auth/register")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(person.json()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/register/verify")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", sms.lastCodeTo(person.e164())))))
                .andExpect(status().isOk());
        var setup = mvc.perform(post("/api/auth/register/totp").session(session))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String secret = JsonPath.read(setup, "$.secret");
        assertThatThrownBy(() -> mvc.perform(post("/api/auth/register/totp/verify")
                        .session(session)
                        .header("User-Agent", FAILING_CLIENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", totpNow(secret))))))
                .hasRootCauseMessage("simulated failure after the account and the event");

        var userId = failedUser.get();
        assertThat(userId).isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM identity.users WHERE id = :u")
                        .param("u", userId)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(publications(userId)).isZero();
        assertThat(recordsFor(userId, Duration.ofSeconds(3))).isEmpty();
    }
}
