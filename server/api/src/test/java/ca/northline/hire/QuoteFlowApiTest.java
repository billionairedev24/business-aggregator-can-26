package ca.northline.hire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.booking.api.BookingConfirmed;
import ca.northline.booking.api.QuoteAccepted;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-56: a customer asks several providers for quotes, compares the itemized, versioned answers and accepts one by
 * paying its escrow deposit (S-51 step-up, Idempotency-Key), which books the proposed time. Fake gateway (profile test).
 */
@RecordApplicationEvents
class QuoteFlowApiTest extends IntegrationTest {

    static final String MECHANIC = "service.automotive.mobile-mechanic";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final ZoneId CALGARY = ZoneId.of("America/Edmonton");

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    HireFixtures fx;
    HireFixtures.Provider wrench;
    HireFixtures.Provider spark;
    HireFixtures.Provider torque;
    String customer;

    @BeforeEach
    void setUp() {
        new CategorySeeder(dataSource).seed();
        fx = new HireFixtures(jdbc);
        wrench = fx.provider("Quote Wrench", "master", List.of("Beltline"));
        spark = fx.provider("Spark Mobile", "trusted", List.of("Beltline"));
        torque = fx.provider("Torque Garage", "registered", List.of("Downtown"));
        for (var p : List.of(wrench, spark, torque)) {
            fx.service(p.merchantId(), MECHANIC, "Alternator / starter", "quote", null, 120);
        }
        customer = data.user("Amara Osei");
    }

    static Instant tomorrowAt(int hour) {
        return LocalDate.now(CALGARY)
                .plusDays(1)
                .atTime(hour, 0)
                .atZone(CALGARY)
                .toInstant();
    }

    Map<String, Object> ask(List<String> providers) {
        var r = new LinkedHashMap<String, Object>();
        r.put("category", "mobile-mechanic");
        r.put("providers", providers);
        r.put("description", "Battery light on, car died twice this week. Probably the alternator.");
        r.put("vehicle", Map.of("year", "2018", "make", "Honda", "model", "Civic"));
        r.put("area", "Beltline");
        r.put("note", "OEM parts preferred");
        return r;
    }

    String requestQuotes(String who, List<String> providers) throws Exception {
        var body = mvc.perform(post("/api/v1/me/quote-requests")
                        .with(TestJwt.customer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(ask(providers))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.requestId");
    }

    /** 1 alternator (part) $240 + 1.5 h labour at $130 = $435.00, GST $21.75, total $456.75, 25 % deposit $114.19. */
    static String alternator(Instant at, int depositBps) {
        return """
                {"lines":[{"kind":"part","description":"Alternator, remanufactured","note":"12-month warranty","qty":1,"unitCents":24000},
                          {"kind":"labour","description":"Replace alternator, check belt","qty":1.5,"unitCents":13000}],
                 "scope":"Confirm the charging fault and replace the alternator.","exclusions":"Serpentine belt extra if worn.",
                 "proposedAt":"%s","durationMin":120,"validHours":72,"warranty":"parts_labour_12m",
                 "depositKind":"pct","depositBps":%d}
                """.formatted(at, depositBps);
    }

    String sendQuote(HireFixtures.Provider p, String requestId, String body) throws Exception {
        var answer = mvc.perform(post("/api/v1/merchants/{m}/quote-requests/{r}/quotes", p.merchantId(), requestId)
                        .with(TestJwt.member(p.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(answer, "$.id");
    }

    String revise(HireFixtures.Provider p, String quoteId, String body) throws Exception {
        var answer = mvc.perform(post("/api/v1/merchants/{m}/quotes/{q}/revisions", p.merchantId(), quoteId)
                        .with(TestJwt.member(p.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(answer, "$.id");
    }

    static String visit() throws Exception {
        return JSON.writeValueAsString(Map.of(
                "addressLine", "1204 17 Ave SW",
                "unit", "Apt 804",
                "accessNote", "Parkade gate 4471, stall P2-118",
                "contactPhone", "+1 403 555 0123"));
    }

    @Nested
    class Requesting {

        @Test
        void goesToTheChosenProviders_withTheAreaButNoAddress() throws Exception {
            mvc.perform(post("/api/v1/me/quote-requests")
                            .with(TestJwt.customer(customer))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(ask(List.of(wrench.slug(), spark.slug())))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ref", startsWith("QT-")))
                    .andExpect(jsonPath("$.providers").value(2))
                    .andExpect(jsonPath("$.respondBy").exists());

            mvc.perform(get("/api/v1/merchants/{m}/quote-requests", wrench.merchantId())
                            .with(TestJwt.member(wrench.owner())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].title").value("Mobile mechanic · 2018 Honda Civic"))
                    .andExpect(jsonPath("$.items[0].area").value("Beltline"));
            mvc.perform(get("/api/v1/merchants/{m}/quote-requests", torque.merchantId())
                            .with(TestJwt.member(torque.owner())))
                    .andExpect(jsonPath("$.items.length()").value(0));
            var details = jdbc.sql("select details::text from booking.quote_requests where customer_id = ?")
                    .params(customer)
                    .query(String.class)
                    .single();
            assertThat(details).contains("OEM parts preferred").doesNotContain("17 Ave");
        }

        @Test
        void validatesEveryField() throws Exception {
            var r = ask(List.of(wrench.slug(), spark.slug(), torque.slug(), "a-fourth"));
            r.put("description", "broken");
            r.put("vehicle", Map.of("year", "2018"));
            mvc.perform(post("/api/v1/me/quote-requests")
                            .with(TestJwt.customer(customer))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(r)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field == 'providers')].message")
                            .value("Choose 1 to 3 providers."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'description')].message")
                            .value("Describe the job in at least 10 characters."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'vehicle')].message")
                            .value("Tell us the vehicle year, make and model."));
        }

        @Test
        void onlyProvidersOfferingTheService_andOnlyQuoteableServices() throws Exception {
            var cleaner = fx.provider("Only Cleans", "master", List.of("Beltline"));
            fx.service(
                    cleaner.merchantId(),
                    "service.cleaning-and-property.house-cleaning",
                    "Clean",
                    "hourly",
                    4500L,
                    120);
            mvc.perform(post("/api/v1/me/quote-requests")
                            .with(TestJwt.customer(customer))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(ask(List.of(wrench.slug(), cleaner.slug())))))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("One of these providers doesn't offer this service any more. Choose again."));
            var barber = ask(List.of(wrench.slug()));
            barber.put("category", "barber-and-hair");
            barber.remove("vehicle");
            mvc.perform(post("/api/v1/me/quote-requests")
                            .with(TestJwt.customer(customer))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(barber)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field == 'category')].message")
                            .value("This service is booked directly — pick a time on a provider's page."));
        }

        @Test
        void needsASignedInCustomer() throws Exception {
            mvc.perform(post("/api/v1/me/quote-requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(ask(List.of(wrench.slug())))))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class Comparing {

        @Test
        void listsEveryProviderAsked_quotedCheapestFirst_thenWaiting() throws Exception {
            var requestId = requestQuotes(customer, List.of(wrench.slug(), spark.slug(), torque.slug()));
            sendQuote(wrench, requestId, alternator(tomorrowAt(10), 2500));
            sendQuote(spark, requestId, """
                    {"lines":[{"kind":"labour","description":"Replace alternator (your part)","qty":2,"unitCents":9000}],
                     "scope":"Labour only.","validHours":24,"warranty":"labour_90d","depositKind":"none"}
                    """);
            mvc.perform(get("/api/v1/me/quote-requests/{id}", requestId).with(TestJwt.customer(customer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ref", startsWith("QT-")))
                    .andExpect(jsonPath("$.categorySlug").value("mobile-mechanic"))
                    .andExpect(jsonPath("$.offers.length()").value(3))
                    .andExpect(jsonPath("$.offers[0].provider.name").value("Spark Mobile"))
                    .andExpect(jsonPath("$.offers[0].quote.totalCents").value(18900))
                    .andExpect(jsonPath("$.offers[1].provider.name").value("Quote Wrench"))
                    .andExpect(jsonPath("$.offers[1].quote.totalCents").value(45675))
                    .andExpect(jsonPath("$.offers[1].quote.lines.length()").value(2))
                    .andExpect(jsonPath("$.offers[2].status").value("waiting"))
                    .andExpect(jsonPath("$.offers[2].quote").doesNotExist());
            mvc.perform(get("/api/v1/me/quote-requests/{id}", requestId).with(TestJwt.customer(data.user("Nosy"))))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class Reading {

        @Test
        void showsEveryLineScopeExclusionsWarrantyDepositAndValidity_andMarksItViewed() throws Exception {
            var requestId = requestQuotes(customer, List.of(wrench.slug(), spark.slug()));
            var quoteId = sendQuote(wrench, requestId, alternator(tomorrowAt(10), 2500));
            mvc.perform(get("/api/v1/me/quotes/{id}", quoteId).with(TestJwt.customer(customer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.quote.version").value(1))
                    .andExpect(jsonPath("$.quote.lines[0].kind").value("part"))
                    .andExpect(jsonPath("$.quote.lines[0].note").value("12-month warranty"))
                    .andExpect(jsonPath("$.quote.lines[1].amountCents").value(19500))
                    .andExpect(jsonPath("$.quote.subtotalCents").value(43500))
                    .andExpect(jsonPath("$.quote.taxCents").value(2175))
                    .andExpect(jsonPath("$.quote.totalCents").value(45675))
                    .andExpect(jsonPath("$.quote.depositCents").value(11419))
                    .andExpect(jsonPath("$.quote.depositBps").value(2500))
                    .andExpect(
                            jsonPath("$.quote.scope").value("Confirm the charging fault and replace the alternator."))
                    .andExpect(jsonPath("$.quote.exclusions").value("Serpentine belt extra if worn."))
                    .andExpect(jsonPath("$.quote.warranty").value("parts_labour_12m"))
                    .andExpect(jsonPath("$.quote.validUntil").exists())
                    .andExpect(jsonPath("$.quote.state").value("viewed"))
                    .andExpect(jsonPath("$.provider.name").value("Quote Wrench"))
                    .andExpect(jsonPath("$.title").value("Mobile mechanic · 2018 Honda Civic"));
            mvc.perform(get("/api/v1/merchants/{m}/quotes/{q}", wrench.merchantId(), quoteId)
                            .with(TestJwt.member(wrench.owner())))
                    .andExpect(jsonPath("$.state").value("viewed"));
            mvc.perform(get("/api/v1/me/quotes/{id}", quoteId).with(TestJwt.customer(data.user("Nosy"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        void aRevisionSupersedesTheVersionTheCustomerSaw() throws Exception {
            var requestId = requestQuotes(customer, List.of(wrench.slug()));
            var v1 = sendQuote(wrench, requestId, alternator(tomorrowAt(10), 2500));
            var v2 = revise(wrench, v1, alternator(tomorrowAt(14), 5000));
            mvc.perform(get("/api/v1/me/quotes/{id}", v1).with(TestJwt.customer(customer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.quote.state").value("superseded"))
                    .andExpect(jsonPath("$.quote.currentQuoteId").value(v2))
                    .andExpect(jsonPath("$.quote.versions.length()").value(2))
                    .andExpect(jsonPath("$.quote.versions[0].version").value(2))
                    .andExpect(jsonPath("$.quote.versions[1].state").value("superseded"));
            mvc.perform(post("/api/v1/me/quotes/{id}/accept", v1)
                            .with(TestJwt.customerWithMfa(customer))
                            .header("Idempotency-Key", "old-" + v1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(visit()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("quote_revised"));
            mvc.perform(get("/api/v1/me/quotes/{id}", v2).with(TestJwt.customer(customer)))
                    .andExpect(jsonPath("$.quote.version").value(2))
                    .andExpect(jsonPath("$.quote.depositCents").value(22838));
        }

        @Test
        void declining_tellsTheProvider_once() throws Exception {
            var requestId = requestQuotes(customer, List.of(wrench.slug()));
            var quoteId = sendQuote(wrench, requestId, alternator(tomorrowAt(10), 2500));
            mvc.perform(post("/api/v1/me/quotes/{id}/decline", quoteId).with(TestJwt.customer(data.user("Nosy"))))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/me/quotes/{id}/decline", quoteId).with(TestJwt.customer(customer)))
                    .andExpect(status().isNoContent());
            mvc.perform(get("/api/v1/merchants/{m}/quotes/{q}", wrench.merchantId(), quoteId)
                            .with(TestJwt.member(wrench.owner())))
                    .andExpect(jsonPath("$.state").value("declined"));
            mvc.perform(post("/api/v1/me/quotes/{id}/decline", quoteId).with(TestJwt.customer(customer)))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    class Accepting {

        @Test
        void followsTheS51StepUpRule_andNeedsTheAddress() throws Exception {
            var requestId = requestQuotes(customer, List.of(wrench.slug()));
            var quoteId = sendQuote(wrench, requestId, alternator(tomorrowAt(10), 2500));
            mvc.perform(post("/api/v1/me/quotes/{id}/accept", quoteId)
                            .with(TestJwt.customerWithMfa(customer))
                            .header("Idempotency-Key", "a0-" + quoteId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"contactPhone\":\"call me\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field == 'addressLine')].message")
                            .value("Pick or enter the address."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'contactPhone')].message")
                            .value("Enter a phone number like +1 403 555 0123."));
            mvc.perform(post("/api/v1/me/quotes/{id}/accept", quoteId)
                            .with(TestJwt.customer(customer))
                            .header("Idempotency-Key", "a1-" + quoteId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(visit()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("second_factor_required"));
            jdbc.sql("update identity.users set mfa_primary = 'totp' where id = ?")
                    .params(customer)
                    .update();
            mvc.perform(post("/api/v1/me/quotes/{id}/accept", quoteId)
                            .with(TestJwt.customer(customer))
                            .header("Idempotency-Key", "a2-" + quoteId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(visit()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("step_up_required"));
            mvc.perform(post("/api/v1/me/quotes/{id}/accept", quoteId)
                            .with(TestJwt.customer(customer))
                            .header("Idempotency-Key", "a3-" + quoteId)
                            .header("X-Step-Up", "dev")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(visit()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("authorized"));
        }

        @Test
        void holdsTheDepositInEscrow_acceptsAndBooksTheProposedTime() throws Exception {
            var at = tomorrowAt(10);
            var requestId = requestQuotes(customer, List.of(wrench.slug(), spark.slug()));
            var quoteId = sendQuote(wrench, requestId, alternator(at, 2500));
            mvc.perform(post("/api/v1/me/quotes/{id}/accept/confirm", quoteId)
                            .with(TestJwt.customer(customer))
                            .header("Idempotency-Key", "early-" + quoteId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(visit()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("not_started"));

            var key = "acc-" + quoteId;
            var started = mvc.perform(post("/api/v1/me/quotes/{id}/accept", quoteId)
                            .with(TestJwt.customerWithMfa(customer))
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(visit()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalCents").value(11419))
                    .andExpect(jsonPath("$.taxCents").value(544))
                    .andExpect(jsonPath("$.amountCents").value(10875))
                    .andExpect(jsonPath("$.status").value("authorized"))
                    .andExpect(jsonPath("$.provider").value("fake"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String bookingId = JsonPath.read(started, "$.bookingId");
            mvc.perform(post("/api/v1/me/quotes/{id}/accept", quoteId)
                            .with(TestJwt.customerWithMfa(customer))
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(visit()))
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(jsonPath("$.bookingId").value(bookingId));

            mvc.perform(post("/api/v1/me/quotes/{id}/accept/confirm", quoteId)
                            .with(TestJwt.customer(customer))
                            .header("Idempotency-Key", "conf-" + quoteId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(visit()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.bookingId").value(bookingId))
                    .andExpect(jsonPath("$.ref", startsWith("BK-")))
                    .andExpect(jsonPath("$.heldCents").value(11419))
                    .andExpect(jsonPath("$.startsAt").value(at.toString()))
                    .andExpect(jsonPath("$.addressLine").value("1204 17 Ave SW, Apt 804"));

            mvc.perform(get("/api/v1/me/quotes/{id}", quoteId).with(TestJwt.customer(customer)))
                    .andExpect(jsonPath("$.quote.state").value("accepted"))
                    .andExpect(jsonPath("$.bookingId").value(bookingId));
            var row = jdbc.sql("select quote_id, escrow_id, details::text, ends_at from booking.bookings where id = ?")
                    .params(bookingId)
                    .query((rs, _) -> List.of(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getTimestamp(4).toInstant().toString()))
                    .single();
            assertThat(row.get(0)).isEqualTo(quoteId);
            assertThat(row.get(2)).contains("QT-").doesNotContain("4471");
            assertThat(row.get(3)).isEqualTo(at.plusSeconds(7200).toString());
            assertThat(jdbc.sql("select amount_cents + tax_cents from payments.escrows where id = ?")
                            .params(row.get(1))
                            .query(Long.class)
                            .single())
                    .isEqualTo(11419L);
            assertThat(jdbc.sql("select count(*) from booking.access_notes where booking_id = ?")
                            .params(bookingId)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1L);
            assertThat(events.stream(QuoteAccepted.class)
                            .filter(e -> e.aggregateId().equals(quoteId)))
                    .singleElement()
                    .satisfies(e -> assertThat(e.depositCents()).isEqualTo(11419));
            assertThat(events.stream(BookingConfirmed.class)
                            .filter(e -> e.aggregateId().equals(bookingId)))
                    .singleElement()
                    .satisfies(e -> assertThat(e.quoteId()).isEqualTo(quoteId));

            // the retry answers the same booking; the other provider's quote can't be accepted by anyone else
            mvc.perform(post("/api/v1/me/quotes/{id}/accept/confirm", quoteId)
                            .with(TestJwt.customer(customer))
                            .header("Idempotency-Key", "conf2-" + quoteId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(visit()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.bookingId").value(bookingId));
            mvc.perform(post("/api/v1/me/quotes/{id}/accept", quoteId)
                            .with(TestJwt.customerWithMfa(data.user("Nosy")))
                            .header("Idempotency-Key", "nosy-" + quoteId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(visit()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void aQuoteWithoutADepositHoldsTheWholeQuote() throws Exception {
            var requestId = requestQuotes(customer, List.of(spark.slug()));
            var quoteId = sendQuote(spark, requestId, """
                    {"lines":[{"kind":"labour","description":"Replace alternator (your part)","qty":2,"unitCents":9000}],
                     "scope":"Labour only.","proposedAt":"%s","validHours":24,"warranty":"labour_90d","depositKind":"none"}
                    """.formatted(tomorrowAt(15)));
            mvc.perform(post("/api/v1/me/quotes/{id}/accept", quoteId)
                            .with(TestJwt.customerWithMfa(customer))
                            .header("Idempotency-Key", "whole-" + quoteId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(visit()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.amountCents").value(18000))
                    .andExpect(jsonPath("$.taxCents").value(900))
                    .andExpect(jsonPath("$.totalCents").value(18900));
        }
    }
}
