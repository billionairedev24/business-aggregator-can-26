package ca.northline.hire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.booking.api.BookingCancelled;
import ca.northline.payments.application.PaymentsJobs;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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

/**
 * S-153 on instant bookings: the provider's late-cancellation terms (Availability › Booking rules within the region's
 * limits) are shown before booking and kept with it; cancelling inside the window keeps the fee from the hold — its own
 * escrow, split by the take rate — and releases the rest; outside it everything is released. Saved cards pay the
 * checkout; a card hold that never became escrow is canceled by the sweeper; Account lists the bookings. Fake gateway.
 */
@RecordApplicationEvents
class BookingMoneyApiTest extends IntegrationTest {

    static final ZoneId ZONE = ZoneId.of("America/Edmonton");

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    @Autowired
    PaymentsJobs jobs;

    BookingFlow flow;
    String customer;

    @BeforeEach
    void setUp() {
        new CategorySeeder(dataSource).seed();
        flow = new BookingFlow(mvc, new HireFixtures(jdbc), "Terms Wrench");
        customer = data.user("Amara Osei");
    }

    /**
     * A free slot about {@code hours} from now, on the hour. The fixture is open 00:00–23:30 every day and the service
     * takes an hour, so a 23:00 start doesn't fit: that one moves to 00:00 (the test failed for an hour a day before).
     */
    static Instant inHours(int hours) {
        var at = Instant.now().plus(Duration.ofHours(hours)).truncatedTo(ChronoUnit.HOURS);
        return at.atZone(ZONE).toLocalTime().getHour() == 23 ? at.plus(Duration.ofHours(1)) : at;
    }

    void rules(int rescheduleFreeMin, Long feeCents, Integer feeBps) {
        jdbc.sql("""
                        update availability.booking_rules
                           set reschedule_free_min = ?, late_cancel_fee_cents = ?, late_cancel_fee_bps = ?
                         where merchant_id = ?""")
                .params(rescheduleFreeMin, feeCents, feeBps, flow.provider.merchantId())
                .update();
    }

    String cancel(String who, String bookingId, String key) throws Exception {
        var response = mvc.perform(post("/api/v1/me/bookings/{id}/cancel", bookingId)
                        .with(TestJwt.customer(who))
                        .header("Idempotency-Key", key))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response.getContentAsString();
    }

    @Nested
    class LateCancellation {

        @Test
        void insideTheWindow_theShareIsKeptFromTheHold_inItsOwnEscrow_andTheRestReleased() throws Exception {
            rules(1440, null, 5000); // free until 24 h before, then 50 % of the job
            var at = inHours(4);
            mvc.perform(get("/api/v1/public/providers/{slug}/slots", flow.provider.slug())
                            .param("serviceId", flow.service))
                    .andExpect(jsonPath("$.cancellation.windowHours").value(24))
                    .andExpect(jsonPath("$.cancellation.feeBps").value(5000));
            var bookingId = flow.book(customer, at, Map.of());
            mvc.perform(get("/api/v1/me/bookings/{id}", bookingId).with(TestJwt.customer(customer)))
                    .andExpect(jsonPath("$.cancellation.windowHours").value(24))
                    .andExpect(jsonPath("$.cancellation.feeBps").value(5000))
                    .andExpect(jsonPath("$.freeCancelUntil")
                            .value(at.minus(Duration.ofHours(24)).toString()));

            // $89 + 5 % = $93.45 held; 50 % of $89 = $44.50 + its tax $2.23 kept, $46.72 released
            mvc.perform(get("/api/v1/me/bookings/{id}/cancellation", bookingId).with(TestJwt.customer(customer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cancellable").value(true))
                    .andExpect(jsonPath("$.late").value(true))
                    .andExpect(jsonPath("$.feeCents").value(4450))
                    .andExpect(jsonPath("$.feeTaxCents").value(223))
                    .andExpect(jsonPath("$.feeTotalCents").value(4673))
                    .andExpect(jsonPath("$.releasedCents").value(4672));

            mvc.perform(post("/api/v1/me/bookings/{id}/cancel", bookingId).with(TestJwt.customer(customer)))
                    .andExpect(status().isUnprocessableContent()); // money moves: Idempotency-Key required
            var answer = cancel(customer, bookingId, "cancel-" + bookingId);
            assertThat(JsonPath.<String>read(answer, "$.state")).isEqualTo("cancelled");
            assertThat(JsonPath.<Integer>read(answer, "$.cancelled.feeCents")).isEqualTo(4450);
            mvc.perform(post("/api/v1/me/bookings/{id}/cancel", bookingId)
                            .with(TestJwt.customer(customer))
                            .header("Idempotency-Key", "cancel-" + bookingId))
                    .andExpect(header().string("Idempotent-Replayed", "true"));
            cancel(customer, bookingId, "again-" + bookingId); // cancelling again changes nothing

            var escrows = jdbc.sql("""
                            select ref_type, state, amount_cents, tax_cents, fee_cents, fulfilled_at is not null,
                                   release_at
                              from payments.escrows where ref_id = ? order by ref_type""")
                    .params(bookingId)
                    .query((rs, _) -> List.of(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getLong(3),
                            rs.getLong(4),
                            rs.getLong(5),
                            rs.getBoolean(6),
                            rs.getTimestamp(7) == null ? "" : "release"))
                    .list();
            assertThat(escrows).hasSize(2);
            assertThat(escrows.get(0)).containsExactly("booking", "refunded", 8900L, 445L, 801L, false, "");
            // the fee escrow: the master tier's 9 % of $44.50, captured now, released on the services clock
            assertThat(escrows.get(1))
                    .containsExactly("booking_cancel_fee", "held", 4450L, 223L, 401L, true, "release");
            assertThat(jdbc.sql("""
                            select pi.state from payments.payment_intents pi
                              join payments.escrows e on e.payment_intent_id = pi.id
                             where e.ref_type = 'booking_cancel_fee' and e.ref_id = ?""").params(bookingId).query(String.class).single())
                    .isEqualTo("captured");
            assertThat(jdbc.sql(
                                    "select state, cancel_fee_cents, cancel_fee_tax_cents from booking.bookings where id = ?")
                            .params(bookingId)
                            .query((rs, _) -> List.of(rs.getString(1), rs.getLong(2), rs.getLong(3)))
                            .single())
                    .containsExactly("cancelled", 4450L, 223L);
            assertThat(events.stream(BookingCancelled.class)
                            .filter(e -> e.aggregateId().equals(bookingId)))
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.late()).isTrue();
                        assertThat(e.feeCents()).isEqualTo(4450);
                        assertThat(e.merchantId()).isEqualTo(flow.provider.merchantId());
                    });
        }

        @Test
        void outsideTheWindow_itIsFree_andAFixedFeeOnlyCountsLate() throws Exception {
            rules(180, 3500L, null); // free until 3 h before, then $35
            var free = flow.book(customer, inHours(8), Map.of());
            mvc.perform(get("/api/v1/me/bookings/{id}/cancellation", free).with(TestJwt.customer(customer)))
                    .andExpect(jsonPath("$.late").value(false))
                    .andExpect(jsonPath("$.feeTotalCents").value(0))
                    .andExpect(jsonPath("$.releasedCents").value(9345));
            cancel(customer, free, "free-" + free);
            assertThat(jdbc.sql("""
                            select e.state || '/' || pi.state from payments.escrows e
                              join payments.payment_intents pi on pi.id = e.payment_intent_id
                             where e.ref_type = 'booking' and e.ref_id = ?""").params(free).query(String.class).single()).isEqualTo("refunded/canceled");
            assertThat(jdbc.sql(
                                    "select count(*) from payments.escrows where ref_type = 'booking_cancel_fee' and ref_id = ?")
                            .params(free)
                            .query(Long.class)
                            .single())
                    .isZero();

            var late = flow.book(customer, inHours(2), Map.of());
            mvc.perform(get("/api/v1/me/bookings/{id}/cancellation", late).with(TestJwt.customer(customer)))
                    .andExpect(jsonPath("$.late").value(true))
                    .andExpect(jsonPath("$.feeCents").value(3500))
                    .andExpect(jsonPath("$.feeTaxCents").value(175))
                    .andExpect(jsonPath("$.feeFixedCents").value(3500));
        }

        @Test
        void theRegionCapsTheFee_andTheStudioCantSetMore() throws Exception {
            rules(1440, null, 5000);
            try {
                jdbc.sql(
                                "update region.regions set cancel_max_fee_bps = 2500 where kind = 'province' and province = 'AB'")
                        .update();
                mvc.perform(get("/api/v1/public/providers/{slug}/slots", flow.provider.slug())
                                .param("serviceId", flow.service))
                        .andExpect(jsonPath("$.cancellation.feeBps").value(2500));
                var rulesPath = "/api/v1/merchants/{m}/availability/rules";
                mvc.perform(get(rulesPath, flow.provider.merchantId()).with(TestJwt.member(flow.provider.owner())))
                        .andExpect(jsonPath("$.maxCancelFeeBps").value(2500))
                        .andExpect(jsonPath("$.maxCancelWindowHours").value(72));
                mvc.perform(put(rulesPath, flow.provider.merchantId())
                                .with(TestJwt.member(flow.provider.owner()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"intervalMin":30,"bufferMin":0,"minNoticeMin":60,"horizonDays":14,"maxJobsPerDay":99,
                                         "acceptMode":"instant","rescheduleFreeMin":1440,"lateCancelFeeBps":5000,
                                         "serviceAreas":["Beltline"]}"""))
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.errors[0].field").value("lateCancelFee"))
                        .andExpect(jsonPath("$.errors[0].message").value("Choose a fee from 0 to 25 percent."));
                mvc.perform(put(rulesPath, flow.provider.merchantId())
                                .with(TestJwt.member(flow.provider.owner()))
                                .header("Accept-Language", "fr-CA")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"intervalMin":30,"bufferMin":0,"minNoticeMin":60,"horizonDays":14,"maxJobsPerDay":99,
                                         "acceptMode":"instant","rescheduleFreeMin":1440,"lateCancelFeeBps":5000,
                                         "serviceAreas":["Beltline"]}"""))
                        .andExpect(jsonPath("$.errors[0].message").value("Choisissez des frais de 0 à 25 pour cent."));
            } finally {
                jdbc.sql(
                                "update region.regions set cancel_max_fee_bps = null where kind = 'province' and province = 'AB'")
                        .update();
            }
        }

        @Test
        void onlyTheCustomer_andOnlyBeforeTheWorkStarts() throws Exception {
            var bookingId = flow.book(customer, inHours(6), Map.of());
            mvc.perform(get("/api/v1/me/bookings/{id}/cancellation", bookingId)
                            .with(TestJwt.customer(data.user("Nosy"))))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/me/bookings/{id}/cancel", bookingId)
                            .with(TestJwt.customer(data.user("Nosy")))
                            .header("Idempotency-Key", "nosy-" + bookingId))
                    .andExpect(status().isNotFound());
            flow.job(bookingId, "en-route", Map.of()).andExpect(status().isOk());
            flow.job(bookingId, "on-site", Map.of()).andExpect(status().isOk());
            mvc.perform(get("/api/v1/me/bookings/{id}/cancellation", bookingId).with(TestJwt.customer(customer)))
                    .andExpect(jsonPath("$.cancellable").value(false));
            mvc.perform(post("/api/v1/me/bookings/{id}/cancel", bookingId)
                            .with(TestJwt.customer(customer))
                            .header("Idempotency-Key", "late-" + bookingId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("job_state"));
        }
    }

    @Test
    void aSavedCardPaysTheCheckout_onlyTheCustomersOwn() throws Exception {
        var setup = JsonPath.<String>read(
                mvc.perform(post("/api/v1/me/payment-methods/setup-intents").with(TestJwt.customer(customer)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.setupIntentId");
        var cards = mvc.perform(post("/api/v1/me/payment-methods")
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"setupIntentId\":\"%s\"}".formatted(setup)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String card = JsonPath.<List<String>>read(cards, "$.items[*].id").getFirst();

        var holdId = flow.hold(customer, inHours(30));
        flow.checkout(customer, holdId, Map.of("paymentMethodId", "pm_not_mine"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("paymentMethodId"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose one of your saved cards."));
        var started = flow.checkout(customer, holdId, Map.of("paymentMethodId", card))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("authorized"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String intent = JsonPath.read(started, "$.paymentIntent");
        assertThat(jdbc.sql("select payment_method_ref from payments.payment_intents where stripe_pi = ?")
                        .params(intent)
                        .query(String.class)
                        .single())
                .isEqualTo(card);
        flow.confirm(customer, holdId);
    }

    @Test
    void aCardHoldThatNeverBecameEscrow_isCanceledByTheSweeper_heldOnesAreLeftAlone() throws Exception {
        var lapsed = flow.hold(customer, inHours(30));
        var started = flow.checkout(customer, lapsed, Map.of())
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String unheld = JsonPath.read(started, "$.paymentIntent");
        var booked = flow.book(customer, inHours(32), Map.of());
        jdbc.sql("""
                        update payments.payment_intents set created_at = now() - interval '2 hours'
                         where ref_id in (?, ?)""")
                .params(JsonPath.<String>read(started, "$.bookingId"), booked)
                .update();

        assertThat(jobs.cancelUnheldBookingPayments()).isPositive();
        assertThat(jdbc.sql("select state from payments.payment_intents where stripe_pi = ?")
                        .params(unheld)
                        .query(String.class)
                        .single())
                .isEqualTo("canceled");
        assertThat(jdbc.sql("""
                        select pi.state from payments.payment_intents pi
                          join payments.escrows e on e.payment_intent_id = pi.id
                         where e.ref_type = 'booking' and e.ref_id = ?""").params(booked).query(String.class).single()).isEqualTo("authorized");
    }

    @Test
    void accountListsTheCustomersBookings_newestStartFirst_withWhatIsHeld() throws Exception {
        var first = flow.book(customer, inHours(30), Map.of());
        var second = flow.book(customer, inHours(54), Map.of());
        mvc.perform(get("/api/v1/me/bookings").with(TestJwt.customer(customer)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(second))
                .andExpect(jsonPath("$.items[1].id").value(first))
                .andExpect(jsonPath("$.items[0].providerName").value("Terms Wrench"))
                .andExpect(jsonPath("$.items[0].heldCents").value(9345))
                .andExpect(jsonPath("$.items[0].balanceState").value("none"))
                .andExpect(jsonPath("$.items[0].href").value("/bookings/" + second));
        mvc.perform(get("/api/v1/me/bookings").with(TestJwt.customer(data.user("Nobody"))))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/me/bookings")).andExpect(status().isUnauthorized());
    }
}
