package ca.northline.hire;

import static ca.northline.hire.BookingFlow.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.MovableClock;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Mobile gaps part 2, live ETA of a service visit: the member on the way shares their position from the Studio (only
 * while en route, only the member doing the job, at most every 5 s); the customer sees minutes away from a straight-line
 * estimate — never the position — and nothing once the member is on site. Positions never reach Postgres.
 */
@Import(MovableClock.Config.class)
class VisitEtaApiTest extends IntegrationTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MovableClock clock;

    BookingFlow flow;
    String customer;

    @BeforeEach
    void setUp() {
        clock.reset();
        new CategorySeeder(dataSource).seed();
        flow = new BookingFlow(mvc, new HireFixtures(jdbc), "Eta Wrench");
        customer = data.user("Amara Osei");
    }

    @AfterEach
    void time() {
        clock.reset();
    }

    ResultActions share(String bookingId, String userId, double lat, double lng) throws Exception {
        return mvc.perform(post("/api/v1/merchants/{m}/jobs/{id}/position", flow.provider.merchantId(), bookingId)
                .with(TestJwt.member(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lat\":%s,\"lng\":%s}".formatted(lat, lng)));
    }

    ResultActions eta(String bookingId, String who) throws Exception {
        return mvc.perform(get("/api/v1/me/bookings/{id}/eta", bookingId).with(TestJwt.customer(who)));
    }

    @Test
    void theCustomerSeesMinutesAwayWhileTheMemberSharesOnTheWay() throws Exception {
        // the job site picked on the map (made-up coordinates)
        var id = flow.book(customer, tomorrowAt(10), Map.of("siteLat", 50.0, "siteLng", -100.0));
        eta(id, customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("confirmed"))
                .andExpect(jsonPath("$.sharing").value(false))
                .andExpect(jsonPath("$.minutesAway").doesNotExist());
        // not on the way yet: nothing to share
        share(id, flow.provider.owner(), 50.05, -100.0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_en_route"));

        flow.job(id, "en-route", Map.of()).andExpect(status().isOk());
        eta(id, customer)
                .andExpect(jsonPath("$.state").value("en_route"))
                .andExpect(jsonPath("$.sharing").value(false));

        // 0.05° of latitude ≈ 5.6 km straight line, 2 min/km → 12 minutes
        share(id, flow.provider.owner(), 50.05, -100.0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.nextInSeconds").value(5));
        eta(id, customer)
                .andExpect(jsonPath("$.sharing").value(true))
                .andExpect(jsonPath("$.minutesAway").value(12))
                .andExpect(jsonPath("$.kmAway").value(5.6))
                .andExpect(jsonPath("$.method").value("straight_line"))
                .andExpect(jsonPath("$.lat").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        // too soon: kept, not replaced
        share(id, flow.provider.owner(), 50.01, -100.0)
                .andExpect(jsonPath("$.accepted").value(false));
        eta(id, customer).andExpect(jsonPath("$.minutesAway").value(12));
        clock.advance(Duration.ofSeconds(6));
        share(id, flow.provider.owner(), 50.01, -100.0)
                .andExpect(jsonPath("$.accepted").value(true));
        eta(id, customer).andExpect(jsonPath("$.minutesAway").value(3));

        // a position older than 5 minutes isn't live any more
        clock.advance(Duration.ofMinutes(6));
        eta(id, customer)
                .andExpect(jsonPath("$.sharing").value(false))
                .andExpect(jsonPath("$.minutesAway").doesNotExist());

        // arriving ends the sharing
        clock.advance(Duration.ofSeconds(6));
        share(id, flow.provider.owner(), 50.001, -100.0)
                .andExpect(jsonPath("$.accepted").value(true));
        flow.job(id, "on-site", Map.of()).andExpect(status().isOk());
        eta(id, customer)
                .andExpect(jsonPath("$.state").value("on_site"))
                .andExpect(jsonPath("$.sharing").value(false));

        // no coordinate of a provider's live position in Postgres
        assertThat(jdbc.sql("""
                        select count(*) from information_schema.columns
                         where table_schema = 'booking' and (column_name like '%provider%lat%' or column_name like '%live%')""").query(Long.class).single()).isZero();
    }

    @Test
    void withoutALocatedSite_sharingShowsButNoMinutes_andTheRulesHold() throws Exception {
        var id = flow.book(customer, tomorrowAt(12), Map.of());
        flow.job(id, "en-route", Map.of()).andExpect(status().isOk());
        share(id, flow.provider.owner(), 50.05, -100.0).andExpect(status().isOk());
        eta(id, customer)
                .andExpect(jsonPath("$.sharing").value(true))
                .andExpect(jsonPath("$.minutesAway").doesNotExist());

        // someone else's booking: 404
        eta(id, data.user("Nosy")).andExpect(status().isNotFound());
        // a bookkeeper can't share (OPERATE), a stranger is not a member, a technician not on the job can't either
        var bookkeeper = data.user("Book Keeper");
        data.member(flow.provider.merchantId(), bookkeeper, MerchantRole.BOOKKEEPER);
        share(id, bookkeeper, 50.0, -100.0).andExpect(status().isForbidden());
        share(id, data.user("Stranger"), 50.0, -100.0).andExpect(status().isForbidden());
        var tech = data.user("Other Tech");
        data.member(flow.provider.merchantId(), tech, MerchantRole.TECHNICIAN);
        share(id, tech, 50.0, -100.0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_your_job"));
        mvc.perform(post("/api/v1/merchants/{m}/jobs/{id}/position", flow.provider.merchantId(), id)
                        .with(TestJwt.memberWithoutMfa(flow.provider.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\":50,\"lng\":-100}"))
                .andExpect(status().isForbidden());
        // a position off the map
        share(id, flow.provider.owner(), 95.0, -100.0)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Location is not a valid position."));
        // the member switches it off
        mvc.perform(delete("/api/v1/merchants/{m}/jobs/{id}/position", flow.provider.merchantId(), id)
                        .with(TestJwt.member(flow.provider.owner())))
                .andExpect(status().isNoContent());
        eta(id, customer).andExpect(jsonPath("$.sharing").value(false));
        // the site must be a real place
        var hold = flow.hold(customer, tomorrowAt(14));
        flow.checkout(customer, hold, Map.of("siteLat", 50.0))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Pick the address on the map again."));
    }
}
