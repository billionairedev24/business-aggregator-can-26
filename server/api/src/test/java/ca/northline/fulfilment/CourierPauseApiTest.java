package ca.northline.fulfilment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-81: a dispatcher pauses a courier (no new runs, the reason in the platform audit log) and resumes them; a paused
 * courier can't be given a run by hand either.
 */
class CourierPauseApiTest extends IntegrationTest {

    static final String MARKET = "Pauseville";

    @Autowired
    JdbcClient jdbc;

    String staff;
    String courierId;

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @BeforeEach
    void courier() throws Exception {
        staff = data.user("Dee Dispatcher");
        var user = data.user("Sam Courier");
        var body = mvc.perform(json(
                                post("/api/v1/console/fulfilment/couriers"),
                                "{\"userId\":\"%s\",\"market\":\"%s\",\"vehicle\":\"car\"}".formatted(user, MARKET))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        courierId = JsonPath.read(body, "$.id");
    }

    long audits(String action) {
        return jdbc.sql("""
                        select count(*) from developer.audit_log
                         where action = :a and target_id = :c and actor_id = :s and merchant_id is null""")
                .param("a", action)
                .param("c", courierId)
                .param("s", staff)
                .query(Long.class)
                .single();
    }

    @Test
    void pauseAndResumeAreAuditedWithTheReason() throws Exception {
        mvc.perform(json(
                                post("/api/v1/console/fulfilment/couriers/{id}/pause", courierId),
                                "{\"reason\":\"App offline mid-run, 3rd time\"}")
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(courierId))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.name").value("Sam Courier"));
        assertThat(audits("fulfilment.courier_paused")).isEqualTo(1);
        assertThat(jdbc.sql("""
                        select after ->> 'reason' from developer.audit_log
                         where action = 'fulfilment.courier_paused' and target_id = :c""").param("c", courierId).query(String.class).single())
                .isEqualTo("App offline mid-run, 3rd time");
        // pausing again changes nothing and logs nothing
        mvc.perform(json(post("/api/v1/console/fulfilment/couriers/{id}/pause", courierId), "{\"reason\":\"again\"}")
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isOk());
        assertThat(audits("fulfilment.courier_paused")).isEqualTo(1);

        mvc.perform(post("/api/v1/console/fulfilment/couriers/{id}/resume", courierId)
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        assertThat(audits("fulfilment.courier_resumed")).isEqualTo(1);
    }

    @Test
    void aPausedCourierCantBeGivenARunByHand() throws Exception {
        mvc.perform(json(
                                post("/api/v1/console/fulfilment/couriers/{id}/pause", courierId),
                                "{\"reason\":\"Vehicle in the shop\"}")
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isOk());
        var run = Ids.next();
        jdbc.sql("insert into fulfilment.runs (id, kind, state, market) values (:id, 'pooled', 'planned', :m)")
                .param("id", run)
                .param("m", MARKET)
                .update();
        mvc.perform(json(
                                post("/api/v1/console/fulfilment/runs/{id}/assign", run),
                                "{\"courierId\":\"%s\"}".formatted(courierId))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("courier_busy"));
    }

    @Test
    void aReasonIsRequired() throws Exception {
        mvc.perform(json(post("/api/v1/console/fulfilment/couriers/{id}/pause", courierId), "{\"reason\":\"  \"}")
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].message").value("Say why the courier is paused."));
        mvc.perform(json(
                                post("/api/v1/console/fulfilment/couriers/{id}/pause", courierId),
                                "{\"reason\":\"%s\"}".formatted("x".repeat(501)))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Keep the reason under 500 characters."));
        mvc.perform(json(post("/api/v1/console/fulfilment/couriers/{id}/pause", Ids.next()), "{\"reason\":\"x\"}")
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyTheDispatchActionMayPauseOrResume() throws Exception {
        // support opens orders but not delivery; finance and analysts neither; trust & safety neither
        for (var role :
                new StaffRole[] {StaffRole.SUPPORT, StaffRole.FINANCE, StaffRole.ANALYST, StaffRole.TRUST_SAFETY}) {
            mvc.perform(json(post("/api/v1/console/fulfilment/couriers/{id}/pause", courierId), "{\"reason\":\"x\"}")
                            .with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            mvc.perform(post("/api/v1/console/fulfilment/couriers/{id}/resume", courierId)
                            .with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(json(post("/api/v1/console/fulfilment/couriers/{id}/pause", courierId), "{\"reason\":\"x\"}")
                        .with(TestJwt.staffWithoutMfa(staff, StaffRole.DISPATCH)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        // a role view the person doesn't hold is refused
        mvc.perform(json(post("/api/v1/console/fulfilment/couriers/{id}/pause", courierId), "{\"reason\":\"x\"}")
                        .header("X-Console-Role", "admin")
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("role_not_held"));
        assertThat(audits("fulfilment.courier_paused")).isZero();
    }
}
