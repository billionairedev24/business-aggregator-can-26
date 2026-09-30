package ca.northline.hire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.booking.api.BookingConfirmed;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
 * S-55: the booking wizard's server side — the provider's live calendar, slot holds (races included), the escrow
 * payment with Idempotency-Key and step-up, the confirmation and {@code booking.confirmed}. Fake gateway (profile test).
 */
@RecordApplicationEvents
class BookingCheckoutApiTest extends IntegrationTest {

    static final String MECHANIC = "service.automotive.mobile-mechanic";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    HireFixtures fx;
    HireFixtures.Provider provider;
    String service;
    String customer;

    @BeforeEach
    void setUp() {
        new CategorySeeder(dataSource).seed();
        fx = new HireFixtures(jdbc);
        provider = fx.provider("Checkout Wrench", "master", List.of("Beltline"));
        service = fx.service(provider.merchantId(), MECHANIC, "Brake inspection", "fixed", 8900L, 60);
        customer = data.user("Amara Osei");
    }

    /** Tomorrow at 10:00 Calgary time — inside the fixture's 00:00–23:30 hours, past the 60-min notice. */
    static Instant tomorrowAt(int hour) {
        return LocalDate.now(ZoneId.of("America/Edmonton"))
                .plusDays(1)
                .atTime(hour, 0)
                .atZone(ZoneId.of("America/Edmonton"))
                .toInstant();
    }

    String hold(String who, Instant at) throws Exception {
        var body = mvc.perform(post("/api/v1/me/bookings/holds")
                        .with(TestJwt.customer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                Map.of("slug", provider.slug(), "serviceId", service, "startsAt", at.toString()))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.holdId");
    }

    Map<String, Object> request(String holdId) {
        var r = new java.util.LinkedHashMap<String, Object>();
        r.put("holdId", holdId);
        r.put("serviceId", service);
        r.put("description", "Grinding noise when braking, worse when cold.");
        r.put("vehicle", Map.of("year", "2018", "make", "Honda", "model", "Civic", "plate", "BKT 4471"));
        r.put("addressLine", "1204 17 Ave SW");
        r.put("unit", "Apt 804");
        r.put("area", "Beltline");
        r.put("spot", "Underground parkade");
        r.put("accessNote", "Gate code 4471, stall P2-118");
        r.put("contactPhone", "+1 403 555 0123");
        r.put("agreePolicies", true);
        r.put("agreeTerms", true);
        return r;
    }

    @Nested
    class Calendar {

        @Test
        void isPublic_andAnotherCustomersHoldTakesTheSlot() throws Exception {
            var at = tomorrowAt(10);
            hold(data.user("Someone Else"), at);
            var date = LocalDate.now(ZoneId.of("America/Edmonton")).plusDays(1);
            mvc.perform(get("/api/v1/public/providers/{slug}/slots", provider.slug())
                            .param("serviceId", service)
                            .param("from", date.toString())
                            .param("days", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.durationMin").value(60))
                    .andExpect(jsonPath("$.days[0].date").value(date.toString()))
                    .andExpect(jsonPath("$.days[0].slots[?(@.startsAt == '%s')].free".formatted(at))
                            .value(false))
                    .andExpect(jsonPath("$.days[0].slots[?(@.startsAt == '%s')].free".formatted(tomorrowAt(13)))
                            .value(true));
        }

        @Test
        void theCustomersOwnHoldStaysFreeForThem() throws Exception {
            var at = tomorrowAt(11);
            hold(customer, at);
            mvc.perform(get("/api/v1/public/providers/{slug}/slots", provider.slug())
                            .with(TestJwt.customer(customer))
                            .param("serviceId", service)
                            .param(
                                    "from",
                                    LocalDate.now(ZoneId.of("America/Edmonton"))
                                            .plusDays(1)
                                            .toString())
                            .param("days", "1"))
                    .andExpect(jsonPath("$.days[0].slots[?(@.startsAt == '%s')].free".formatted(at))
                            .value(true));
        }
    }

    @Nested
    class Holds {

        @Test
        void needSignIn_aRealService_andAFreeSlot() throws Exception {
            var body = JSON.writeValueAsString(Map.of(
                    "slug",
                    provider.slug(),
                    "serviceId",
                    service,
                    "startsAt",
                    tomorrowAt(9).toString()));
            mvc.perform(post("/api/v1/me/bookings/holds")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/me/bookings/holds")
                            .with(TestJwt.customer(customer))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(Map.of(
                                    "slug",
                                    provider.slug(),
                                    "serviceId",
                                    "nope",
                                    "startsAt",
                                    tomorrowAt(9).toString()))))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/me/bookings/holds")
                            .with(TestJwt.customer(customer))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(Map.of("slug", provider.slug(), "serviceId", service))))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("startsAt"))
                    .andExpect(jsonPath("$.errors[0].message").value("Pick a time."));
            // 3 am tomorrow is fine, 3:15 is between the 30-minute starts
            mvc.perform(post("/api/v1/me/bookings/holds")
                            .with(TestJwt.customer(customer))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(Map.of(
                                    "slug",
                                    provider.slug(),
                                    "serviceId",
                                    service,
                                    "startsAt",
                                    tomorrowAt(3).plus(15, ChronoUnit.MINUTES).toString()))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("slot_taken"));
        }

        @Test
        void quotedServicesAreNotBookedHere() throws Exception {
            var quoted = fx.service(provider.merchantId(), MECHANIC, "Alternator", "quote", null, 90);
            mvc.perform(post("/api/v1/me/bookings/holds")
                            .with(TestJwt.customer(customer))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(Map.of(
                                    "slug",
                                    provider.slug(),
                                    "serviceId",
                                    quoted,
                                    "startsAt",
                                    tomorrowAt(9).toString()))))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("This service is priced by quote — ask for a quote instead."));
        }

        @Test
        void aRaceForTheLastMembersSlotHasOneWinner() throws Exception {
            var at = tomorrowAt(14);
            var customers =
                    List.of(data.user("Racer 1"), data.user("Racer 2"), data.user("Racer 3"), data.user("Racer 4"));
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Callable<Integer>>();
            for (var who : customers) {
                tasks.add(() -> {
                    start.await();
                    return mvc.perform(post("/api/v1/me/bookings/holds")
                                    .with(TestJwt.customer(who))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(JSON.writeValueAsString(Map.of(
                                            "slug", provider.slug(), "serviceId", service, "startsAt", at.toString()))))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                });
            }
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = tasks.stream().map(pool::submit).toList();
                start.countDown();
                var statuses = new ArrayList<Integer>();
                for (var f : futures) {
                    statuses.add(f.get());
                }
                assertThat(statuses).containsOnly(201, 409);
                assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
            }
        }

        @Test
        void aNewHoldReplacesTheCustomersLast_andReleasingFreesIt() throws Exception {
            var first = hold(customer, tomorrowAt(15));
            hold(customer, tomorrowAt(16));
            // the first slot is free again for someone else
            var other = hold(data.user("Next Customer"), tomorrowAt(15));
            mvc.perform(delete("/api/v1/me/bookings/holds/{id}", other).with(TestJwt.customer(customer)))
                    .andExpect(status().isNoContent()); // not theirs: nothing happens
            mvc.perform(post("/api/v1/me/bookings/holds/{id}/confirm", first)
                            .with(TestJwt.customer(customer))
                            .header("Idempotency-Key", "k-" + first))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("hold_expired"));
        }
    }

    @Nested
    class Checkout {

        @Test
        void validatesTheWizard() throws Exception {
            var holdId = hold(customer, tomorrowAt(8));
            var r = request(holdId);
            r.put("description", "short");
            r.put("vehicle", Map.of("year", "2018"));
            r.put("accessNote", "");
            r.put("contactPhone", "call me");
            r.put("agreePolicies", false);
            mvc.perform(post("/api/v1/me/bookings/checkout")
                            .with(TestJwt.customerWithMfa(customer))
                            .header("Idempotency-Key", "v-" + holdId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(r)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field == 'description')].message")
                            .value("Describe the problem in at least 10 characters."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'vehicle.make')].message")
                            .value("Tell us the vehicle year, make and model."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'accessNote')].message")
                            .value("Add access instructions (3+ characters)."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'contactPhone')].message")
                            .value("Enter a phone number like +1 403 555 0123."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'agreePolicies')].message")
                            .value("Accept the cancellation policy to continue."));
            mvc.perform(post("/api/v1/me/bookings/checkout")
                            .with(TestJwt.customerWithMfa(customer))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(request(holdId))))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Idempotency-Key header is required."));
        }

        @Test
        void aPhoneCodeSessionStepsUpBeforePaying() throws Exception {
            var holdId = hold(customer, tomorrowAt(12));
            mvc.perform(post("/api/v1/me/bookings/checkout")
                            .with(TestJwt.customer(customer))
                            .header("Idempotency-Key", "s-" + holdId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(request(holdId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("step_up_required"));
            // the proof northline-auth issues after a passkey / authenticator code ('dev' under test)
            mvc.perform(post("/api/v1/me/bookings/checkout")
                            .with(TestJwt.customer(customer))
                            .header("Idempotency-Key", "s2-" + holdId)
                            .header("X-Step-Up", "dev")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(request(holdId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("authorized"));
        }

        @Test
        void paysIntoEscrow_confirms_andPublishesBookingConfirmed() throws Exception {
            var at = tomorrowAt(17);
            var holdId = hold(customer, at);
            var key = "pay-" + holdId;
            var body = JSON.writeValueAsString(request(holdId));
            var checkout = mvc.perform(post("/api/v1/me/bookings/checkout")
                            .with(TestJwt.customerWithMfa(customer))
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.priceCents").value(8900))
                    .andExpect(jsonPath("$.taxCents").value(445))
                    .andExpect(jsonPath("$.totalCents").value(9345))
                    .andExpect(jsonPath("$.status").value("authorized"))
                    .andExpect(jsonPath("$.paymentIntent", startsWith("pi_")))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String bookingId = JsonPath.read(checkout, "$.bookingId");
            // a retry with the same key answers the same, without a second PaymentIntent
            mvc.perform(post("/api/v1/me/bookings/checkout")
                            .with(TestJwt.customerWithMfa(customer))
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(jsonPath("$.bookingId").value(bookingId));

            mvc.perform(post("/api/v1/me/bookings/holds/{id}/confirm", holdId)
                            .with(TestJwt.customer(customer))
                            .header("Idempotency-Key", "c-" + holdId))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.bookingId").value(bookingId))
                    .andExpect(jsonPath("$.ref", startsWith("BK-")))
                    .andExpect(jsonPath("$.providerName").value("Checkout Wrench"))
                    .andExpect(jsonPath("$.heldCents").value(9345))
                    .andExpect(jsonPath("$.startsAt").value(at.toString()));

            var row = jdbc.sql(
                            "select state, member_user_id, escrow_id, details::text as details, source, address_line from booking.bookings where id = ?")
                    .params(bookingId)
                    .query((rs, _) -> Map.of(
                            "state", rs.getString(1),
                            "member", rs.getString(2),
                            "escrow", rs.getString(3),
                            "details", rs.getString(4),
                            "source", rs.getString(5),
                            "address", rs.getString(6)))
                    .single();
            assertThat(row.get("state")).isEqualTo("confirmed");
            assertThat(row.get("member")).isEqualTo(provider.owner());
            assertThat(row.get("source")).isEqualTo("customer");
            assertThat(row.get("address")).isEqualTo("1204 17 Ave SW, Apt 804");
            assertThat(row.get("details"))
                    .contains("2018 Honda Civic · BKT 4471")
                    .doesNotContain("4471, stall")
                    .doesNotContain("403 555");
            assertThat(jdbc.sql("select count(*) from payments.escrows where id = ? and ref_id = ?")
                            .params(row.get("escrow"), bookingId)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
            assertThat(jdbc.sql(
                                    "select count(*) from booking.access_notes where booking_id = ? and ciphertext is not null")
                            .params(bookingId)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
            var confirmed = events.stream(BookingConfirmed.class)
                    .filter(e -> e.aggregateId().equals(bookingId))
                    .toList();
            assertThat(confirmed).singleElement().satisfies(e -> {
                assertThat(e.memberUserId()).isEqualTo(provider.owner());
                assertThat(e.priceCents()).isEqualTo(8900);
                assertThat(e.bookingType()).isEqualTo("visit");
            });
            // the slot is taken now, and the customer sees their booking; nobody else does
            mvc.perform(get("/api/v1/me/bookings/{id}", bookingId).with(TestJwt.customer(customer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("Brake inspection"));
            mvc.perform(get("/api/v1/me/bookings/{id}", bookingId).with(TestJwt.customer(data.user("Nosy"))))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/me/bookings/holds")
                            .with(TestJwt.customer(data.user("Late Customer")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(
                                    Map.of("slug", provider.slug(), "serviceId", service, "startsAt", at.toString()))))
                    .andExpect(status().isConflict());
            // the Studio's job card: access instructions only around the visit (tomorrow ≫ 1 h away)
            mvc.perform(get("/api/v1/merchants/{m}/jobs/{id}", provider.merchantId(), bookingId)
                            .with(TestJwt.member(provider.owner())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.access").doesNotExist());
        }

        @Test
        void aFreeConsultationBooksWithoutPayment() throws Exception {
            var agent = fx.provider("Consult Realty", "trusted", List.of());
            var consult = fx.service(
                    agent.merchantId(),
                    "service.professional.real-estate-agent",
                    "Buyer consultation",
                    "quote",
                    null,
                    60);
            var body = mvc.perform(post("/api/v1/me/bookings/holds")
                            .with(TestJwt.customer(customer))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(Map.of(
                                    "slug",
                                    agent.slug(),
                                    "serviceId",
                                    consult,
                                    "startsAt",
                                    tomorrowAt(10).toString()))))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String holdId = JsonPath.read(body, "$.holdId");
            mvc.perform(post("/api/v1/me/bookings/checkout")
                            .with(TestJwt.customer(customer))
                            .header("Idempotency-Key", "free-" + holdId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(Map.of(
                                    "holdId",
                                    holdId,
                                    "serviceId",
                                    consult,
                                    "consult",
                                    Map.of("goal", "Buy"),
                                    "addressLine",
                                    "1204 17 Ave SW"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("confirmed"))
                    .andExpect(jsonPath("$.totalCents").value(0))
                    .andExpect(jsonPath("$.booking.ref", startsWith("BK-")))
                    .andExpect(jsonPath("$.booking.heldCents").value(0));
        }
    }
}
