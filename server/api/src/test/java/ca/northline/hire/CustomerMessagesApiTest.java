package ca.northline.hire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.messaging.api.MessageSent;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-153: customer↔provider messages on quote requests and bookings — the customer's "Ask a question" opens the
 * Studio's own customer thread; both sides read and write it; the customer's messages are masked and flagged for
 * off-platform payment (S-93) like the business's; someone else's thread is a 404; the business can open the thread
 * from a quote request or a job (OPERATE + second factor).
 */
@RecordApplicationEvents
class CustomerMessagesApiTest extends IntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    HireFixtures fx;
    HireFixtures.Provider wrench;
    HireFixtures.Provider spark;
    String customer;
    String requestId;

    @BeforeEach
    void setUp() throws Exception {
        new CategorySeeder(dataSource).seed();
        fx = new HireFixtures(jdbc);
        wrench = fx.provider("Chat Wrench", "master", List.of("Beltline"));
        spark = fx.provider("Chat Spark", "trusted", List.of("Beltline"));
        for (var p : List.of(wrench, spark)) {
            fx.service(p.merchantId(), BookingFlow.MECHANIC, "Alternator / starter", "quote", null, 120);
        }
        customer = data.user("Amara Osei");
        var body = mvc.perform(post("/api/v1/me/quote-requests")
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "category", "mobile-mechanic",
                                "providers", List.of(wrench.slug()),
                                "description", "Battery light on, car died twice this week.",
                                "vehicle", Map.of("year", "2018", "make", "Honda", "model", "Civic"),
                                "area", "Beltline"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        requestId = JsonPath.read(body, "$.requestId");
    }

    String ask(String who, String merchantId) throws Exception {
        var body = mvc.perform(post("/api/v1/me/threads")
                        .with(TestJwt.customer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                Map.of("refType", "quote", "refId", requestId, "merchantId", merchantId))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.thread.id");
    }

    @Test
    void askAQuestion_theStudioSeesItInMessages_andTheCustomerReadsTheReply() throws Exception {
        var thread = ask(customer, wrench.merchantId());
        assertThat(ask(customer, wrench.merchantId())).isEqualTo(thread); // idempotent per business and job

        mvc.perform(post("/api/v1/me/threads/{t}/messages", thread)
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Do you bring the part or should I order it?\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.from").value("me"))
                .andExpect(jsonPath("$.flagged").value(false));

        mvc.perform(get("/api/v1/merchants/{m}/threads", wrench.merchantId()).with(TestJwt.member(wrench.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '%s')].counterpartName".formatted(thread))
                        .value("A. Osei"))
                .andExpect(jsonPath(
                        "$.items[?(@.id == '%s')].refCode".formatted(thread),
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith("QT-"))))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].unread".formatted(thread))
                        .value(true));

        mvc.perform(post("/api/v1/merchants/{m}/threads/{t}/messages", wrench.merchantId(), thread)
                        .with(TestJwt.member(wrench.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"I bring a remanufactured one, 12-month warranty.\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/me/threads").with(TestJwt.customer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(thread))
                .andExpect(jsonPath("$.items[0].businessName").value("Chat Wrench"))
                .andExpect(jsonPath("$.items[0].href").value("/quotes/requests/" + requestId))
                .andExpect(jsonPath("$.items[0].unread").value(true));
        mvc.perform(get("/api/v1/me/threads/{t}", thread).with(TestJwt.customer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].from").value("me"))
                .andExpect(jsonPath("$.messages[1].from").value("business"))
                .andExpect(jsonPath("$.messages[1].body").value("I bring a remanufactured one, 12-month warranty."));
        mvc.perform(post("/api/v1/me/threads/{t}/read", thread).with(TestJwt.customer(customer)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/threads").with(TestJwt.customer(customer)))
                .andExpect(jsonPath("$.items[0].unread").value(false));

        assertThat(events.stream(MessageSent.class).filter(e -> e.aggregateId().equals(thread)))
                .extracting(MessageSent::senderRole)
                .containsExactly("customer", "merchant");
        // no PII in the event: ids, kinds and the flag only
        assertThat(events.stream(MessageSent.class).filter(e -> e.aggregateId().equals(thread)))
                .allSatisfy(e -> assertThat(e.toString()).doesNotContain("Amara", "part", "warranty"));
    }

    @Test
    void theCustomersOffPlatformPayment_isMasked_flagged_andOpensATrustFlag() throws Exception {
        var thread = ask(customer, wrench.merchantId());
        var body = mvc.perform(post("/api/v1/me/threads/{t}/messages", thread)
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Can I e-transfer you instead? Call me at 403-555-0188\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flagged").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String text = JsonPath.read(body, "$.body");
        assertThat(text).contains("•••-•••-••••").doesNotContain("0188");
        String messageId = JsonPath.read(body, "$.id");
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(jdbc.sql("""
                                select count(*) from trust.flags
                                 where target_type = 'message' and target_id = ? and rule = 'off_platform_payment'
                                   and merchant_id = ?""")
                                .params(messageId, wrench.merchantId())
                                .query(Long.class)
                                .single())
                        .isEqualTo(1L));
    }

    @Test
    void onlyTheProvidersAsked_onlyTheCustomersOwnThreads_andTheMessagesAreChecked() throws Exception {
        mvc.perform(post("/api/v1/me/threads")
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                Map.of("refType", "quote", "refId", requestId, "merchantId", spark.merchantId()))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("This provider wasn't asked for this quote."));
        mvc.perform(post("/api/v1/me/threads")
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a quote request or a booking."));
        var nosy = data.user("Nosy Neighbour");
        mvc.perform(post("/api/v1/me/threads")
                        .with(TestJwt.customer(nosy))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                Map.of("refType", "quote", "refId", requestId, "merchantId", wrench.merchantId()))))
                .andExpect(status().isNotFound());

        var thread = ask(customer, wrench.merchantId());
        mvc.perform(get("/api/v1/me/threads/{t}", thread).with(TestJwt.customer(nosy)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/me/threads/{t}/messages", thread)
                        .with(TestJwt.customer(nosy))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"hello\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/me/threads/{t}/messages", thread)
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"   \"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("body"))
                .andExpect(jsonPath("$.errors[0].message").value("Write a message."));
        mvc.perform(post("/api/v1/me/threads/{t}/messages", thread)
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("body", "x".repeat(2001)))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Keep messages under 2,000 characters."));
        mvc.perform(get("/api/v1/me/threads")).andExpect(status().isUnauthorized());
    }

    @Test
    void theBusinessOpensTheThreadFromTheQuoteRequest_withASecondFactor() throws Exception {
        var path = "/api/v1/merchants/{m}/quote-requests/{r}/thread";
        mvc.perform(post(path, wrench.merchantId(), requestId).with(TestJwt.member(data.user("Not a member"))))
                .andExpect(status().isForbidden());
        mvc.perform(post(path, wrench.merchantId(), requestId).with(TestJwt.memberWithoutMfa(wrench.owner())))
                .andExpect(status().isForbidden());
        var bookkeeper = data.user("Book Keeper");
        data.member(wrench.merchantId(), bookkeeper, MerchantRole.BOOKKEEPER);
        mvc.perform(post(path, wrench.merchantId(), requestId).with(TestJwt.member(bookkeeper)))
                .andExpect(status().isForbidden());
        mvc.perform(post(path, spark.merchantId(), requestId).with(TestJwt.member(spark.owner())))
                .andExpect(status().isNotFound()); // not asked: nothing to write about

        var answer = mvc.perform(post(path, wrench.merchantId(), requestId).with(TestJwt.member(wrench.owner())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String thread = JsonPath.read(answer, "$.threadId");
        assertThat(ask(customer, wrench.merchantId())).isEqualTo(thread); // the same thread the customer opens
    }
}
