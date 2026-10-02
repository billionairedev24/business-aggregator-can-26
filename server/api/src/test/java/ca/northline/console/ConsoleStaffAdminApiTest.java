package ca.northline.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-96: the Team screen grants and revokes console roles (admin only, audited; replaces the SQL in the runbook), the
 * audit log viewer filters and pages, staff issue and revoke businesses' API keys, the on-call rota, and my audit trail.
 */
class ConsoleStaffAdminApiTest extends IntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    JdbcClient jdbc;

    String adminId;
    String person;
    String email;

    @BeforeEach
    void people() {
        adminId = data.user("Priya Admin");
        jdbc.sql("insert into identity.platform_roles (user_id, role) values (?, 'staff'), (?, 'admin')")
                .params(adminId, adminId)
                .update();
        person = data.user("Marc Finance");
        email = "marc." + Ids.next().toLowerCase(Locale.ROOT) + "@example.test";
        jdbc.sql("update identity.users set email = ? where id = ?")
                .params(email, person)
                .update();
    }

    ResultActions call(MockHttpServletRequestBuilder request, String body, StaffRole... roles) throws Exception {
        return mvc.perform(
                request.contentType(MediaType.APPLICATION_JSON).content(body).with(TestJwt.staff(adminId, roles)));
    }

    List<String> roles(String userId) {
        return jdbc.sql("select role from identity.platform_roles where user_id = ? order by role")
                .params(userId)
                .query(String.class)
                .list();
    }

    @Test
    void adminsGrantAndRevokeConsoleRoles_audited() throws Exception {
        call(
                        post("/api/v1/console/team/invite"),
                        "{\"email\":\"nobody-" + Ids.next() + "@example.test\",\"role\":\"finance\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("No Northline account uses that email. They sign up first, then you add the role."));
        call(
                        post("/api/v1/console/team/invite"),
                        "{\"email\":\"" + email.toUpperCase(Locale.ROOT) + "\",\"role\":\"wizard\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a role from the list."));
        call(
                        post("/api/v1/console/team/invite"),
                        "{\"email\":\"" + email.toUpperCase(Locale.ROOT) + "\",\"role\":\"finance\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(person))
                .andExpect(jsonPath("$.roles").value(Matchers.contains("finance", "staff")));
        call(post("/api/v1/console/team/{id}/roles", person), "{\"role\":\"analyst\"}", StaffRole.ADMIN)
                .andExpect(status().isOk());
        assertThat(roles(person)).containsExactly("analyst", "finance", "staff");

        mvc.perform(get("/api/v1/console/team").with(TestJwt.staff(adminId, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.roles[?(@.role == 'finance')].actions[0]").value("payouts"))
                .andExpect(jsonPath("$.members[?(@.id == '" + person + "')].roles.length()")
                        .value(3));

        mvc.perform(delete("/api/v1/console/team/{id}/roles/{role}", person, "finance")
                        .with(TestJwt.staff(adminId, StaffRole.ADMIN)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/console/team/{id}/roles/{role}", person, "analyst")
                        .with(TestJwt.staff(adminId, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(0));
        assertThat(roles(person)).isEmpty(); // no console role left: `staff` goes too

        mvc.perform(delete("/api/v1/console/team/{id}/roles/{role}", adminId, "admin")
                        .with(TestJwt.staff(adminId, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("own_admin"));
        assertThat(jdbc.sql(
                                "select action || ':' || coalesce(after->>'role', before->>'role') from developer.audit_log where target_id = ? order by at")
                        .params(person)
                        .query(String.class)
                        .list())
                .containsExactly(
                        "console.role_granted:finance",
                        "console.role_granted:analyst",
                        "console.role_revoked:finance",
                        "console.role_revoked:analyst");
    }

    @Test
    void theAuditLogFiltersAndPages_andMyTrailIsMine() throws Exception {
        var target = "t-" + Ids.next();
        var at = Instant.now().minus(Duration.ofMinutes(5));
        for (var i = 0; i < 55; i++) {
            jdbc.sql("""
                            insert into developer.audit_log (id, actor_id, role, action, target_type, target_id, at, after)
                            values (?, ?, 'admin', 'console.test_action', 'test', ?, ?, '{"n":1}'::jsonb)""")
                    .params(Ids.next(), adminId, target, ca.northline.shared.JdbcTimes.ts(at.plusSeconds(i)))
                    .update();
        }
        var first = mvc.perform(get("/api/v1/console/audit")
                        .param("target", target)
                        .param("action", "console.")
                        .with(TestJwt.staff(adminId, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(50))
                .andExpect(jsonPath("$.items[0].actorName").value("Priya Admin"))
                .andExpect(jsonPath("$.items[0].after.n").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var next = JSON.readTree(first).get("next").asString();
        mvc.perform(get("/api/v1/console/audit")
                        .param("target", target)
                        .param("before", next)
                        .with(TestJwt.staff(adminId, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.next").value(Matchers.nullValue()));
        mvc.perform(get("/api/v1/console/audit")
                        .param("before", "garbage!")
                        .with(TestJwt.staff(adminId, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("That page link is no longer valid. Start from the first page."));
        mvc.perform(get("/api/v1/console/audit")
                        .param("from", "yesterday")
                        .with(TestJwt.staff(adminId, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/v1/console/me/audit").with(TestJwt.staff(adminId, StaffRole.ANALYST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].actorId").value(Matchers.everyItem(Matchers.is(adminId))));
    }

    @Test
    void staffIssueAndRevokeABusinesssApiKey() throws Exception {
        var business = data.merchant("seller", "S96 Key Shop " + Ids.next().substring(20));
        call(
                        post("/api/v1/console/api-keys"),
                        "{\"merchantId\":\"nope\",\"name\":\"POS\",\"scopes\":[\"orders:read\"]}",
                        StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a business."));
        var issued = call(
                        post("/api/v1/console/api-keys"),
                        "{\"merchantId\":\"" + business + "\",\"name\":\"POS sync\",\"scopes\":[\"orders:read\"]}",
                        StaffRole.ADMIN)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key.businessName").value(Matchers.startsWith("S96 Key Shop")))
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var keyId = JSON.readTree(issued).get("key").get("id").asString();
        mvc.perform(get("/api/v1/console/api-keys").with(TestJwt.staff(adminId, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + keyId + "')].name").value("POS sync"))
                .andExpect(jsonPath("$..secret").isEmpty());
        call(post("/api/v1/console/api-keys/{id}/revoke", keyId), "", StaffRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedAt").isNotEmpty());
        call(post("/api/v1/console/api-keys/{id}/revoke", keyId), "", StaffRole.ADMIN)
                .andExpect(status().isNotFound());
        assertThat(jdbc.sql(
                                "select action || ':' || actor_id from developer.audit_log where target_id = ? and merchant_id = ? order by at")
                        .params(keyId, business)
                        .query(String.class)
                        .list())
                .containsExactly("api_key.issued:" + adminId, "api_key.revoked:" + adminId);
    }

    @Test
    void theOnCallRota() throws Exception {
        jdbc.sql("insert into identity.platform_roles (user_id, role) values (?, 'staff'), (?, 'dispatch')")
                .params(person, person)
                .update();
        var start = Instant.now().minus(Duration.ofHours(1));
        call(
                        post("/api/v1/console/oncall/shifts"),
                        "{\"userId\":\"" + person + "\",\"startsAt\":\"" + start + "\",\"endsAt\":\""
                                + start.minus(Duration.ofHours(2)) + "\",\"duty\":\"\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'duty')].message")
                        .value("Say what the shift covers, 1 to 120 characters."))
                .andExpect(jsonPath("$.errors[?(@.field == 'endsAt')].message")
                        .value("A shift ends after it starts and lasts at most 7 days."));
        var created = call(
                        post("/api/v1/console/oncall/shifts"),
                        "{\"userId\":\"" + person + "\",\"startsAt\":\"" + start + "\",\"endsAt\":\""
                                + start.plus(Duration.ofHours(8)) + "\",\"duty\":\"Dispatch · courier incidents\"}",
                        StaffRole.ADMIN)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var shiftId = JSON.readTree(created).get("id").asString();
        mvc.perform(get("/api/v1/console/oncall").with(TestJwt.staff(adminId, StaffRole.ANALYST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.now[?(@.id == '" + shiftId + "')].name").value("Marc Finance"))
                .andExpect(jsonPath("$.staff[0].email").value(Matchers.nullValue()));
        // someone else can't hand over Marc's shift; Marc can, to the admin
        call(
                        post("/api/v1/console/oncall/shifts/{id}/hand-over", shiftId),
                        "{\"userId\":\"" + adminId + "\"}",
                        StaffRole.ANALYST)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_your_shift"));
        mvc.perform(post("/api/v1/console/oncall/shifts/{id}/hand-over", shiftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + adminId + "\"}")
                        .with(TestJwt.staff(person, StaffRole.DISPATCH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(adminId));
        mvc.perform(delete("/api/v1/console/oncall/shifts/{id}", shiftId).with(TestJwt.staff(adminId, StaffRole.ADMIN)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("select action from developer.audit_log where target_id = ? order by at")
                        .params(shiftId)
                        .query(String.class)
                        .list())
                .containsExactly(
                        "console.oncall_shift_added", "console.oncall_shift_swapped", "console.oncall_shift_removed");
    }

    @Test
    void roleGates() throws Exception {
        for (var role : List.of(
                StaffRole.TRUST_SAFETY,
                StaffRole.FINANCE,
                StaffRole.DISPATCH,
                StaffRole.SUPPORT,
                StaffRole.SUPPORT_LEAD,
                StaffRole.ANALYST)) {
            call(post("/api/v1/console/team/invite"), "{\"email\":\"" + email + "\",\"role\":\"finance\"}", role)
                    .andExpect(status().isForbidden());
            call(post("/api/v1/console/team/{id}/roles", person), "{\"role\":\"finance\"}", role)
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/console/team/{id}/roles/{role}", person, "finance")
                            .with(TestJwt.staff(adminId, role)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/console/api-keys").with(TestJwt.staff(adminId, role)))
                    .andExpect(status().isForbidden());
            call(post("/api/v1/console/api-keys"), "{}", role).andExpect(status().isForbidden());
            call(post("/api/v1/console/api-keys/{id}/revoke", "k"), "", role).andExpect(status().isForbidden());
            call(post("/api/v1/console/oncall/shifts"), "{}", role).andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/console/oncall/shifts/{id}", "s").with(TestJwt.staff(adminId, role)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/console/oncall").with(TestJwt.staff(adminId, role)))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/console/me/audit").with(TestJwt.staff(adminId, role)))
                    .andExpect(status().isOk());
        }
        // the Team screen and its audit log: admin, trust & safety, finance
        for (var role : List.of(StaffRole.DISPATCH, StaffRole.SUPPORT, StaffRole.SUPPORT_LEAD, StaffRole.ANALYST)) {
            mvc.perform(get("/api/v1/console/team").with(TestJwt.staff(adminId, role)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/console/audit").with(TestJwt.staff(adminId, role)))
                    .andExpect(status().isForbidden());
        }
        call(post("/api/v1/console/oncall/shifts/{id}/hand-over", "s"), "{}", StaffRole.ANALYST)
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/console/team").with(TestJwt.staffWithoutMfa(adminId, StaffRole.ADMIN)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/console/oncall").with(TestJwt.customer(adminId)))
                .andExpect(status().isForbidden());
    }
}
