package ca.northline.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.security.StaffAccess;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-90: the staff role model end to end — {@code GET /api/v1/console/me} lists the held roles with their screens and
 * actions, the role view switch is audit-logged, and console endpoints refuse a role that doesn't open their screen or
 * allow their action (the console only hides; the api refuses), with {@code X-Console-Role} narrowing to one role.
 */
class ConsoleRolesApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Nested
    class Me {

        @Test
        void listsTheHeldRoles_inDesignOrder_withTheirScreensAndActions() throws Exception {
            var staff = data.user("Priya");
            mvc.perform(get("/api/v1/console/me").with(TestJwt.staff(staff, StaffRole.FINANCE, StaffRole.TRUST_SAFETY)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(staff))
                    .andExpect(jsonPath("$.roles[*].role").value(contains("trust_safety", "finance")))
                    .andExpect(jsonPath("$.roles[0].screens")
                            .value(contains(
                                    "overview",
                                    "disputes",
                                    "sellers",
                                    "verify",
                                    "vetting",
                                    "trust",
                                    "support",
                                    "team",
                                    "pilot",
                                    "go_live")))
                    .andExpect(jsonPath("$.roles[0].actions")
                            .value(contains("suspend", "decide", "verify", "vet", "support")))
                    .andExpect(jsonPath("$.roles[1].screens")
                            .value(contains("overview", "disputes", "finance", "reports", "team", "go_live")))
                    .andExpect(jsonPath("$.roles[1].actions").value(contains("refund", "payouts", "attest")));
        }

        @Test
        void staffWithoutAConsoleRole_stillReachesTheirProfile_withNoRoles() throws Exception {
            mvc.perform(get("/api/v1/console/me").with(TestJwt.staff(data.user("New"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles").isEmpty());
        }

        @Test
        void withoutASecondFactor_isRefused() throws Exception {
            mvc.perform(get("/api/v1/console/me").with(TestJwt.staffWithoutMfa(data.user("Sam"), StaffRole.ADMIN)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("mfa_required"));
        }

        @Test
        void aMerchantMember_isNotStaff() throws Exception {
            mvc.perform(get("/api/v1/console/me").with(TestJwt.member(data.user("Ravi"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("forbidden"));
        }
    }

    @Nested
    class RoleView {

        @Test
        void switchingToAHeldRole_answersItsGrant_andIsAuditLogged() throws Exception {
            var staff = data.user("Dev");
            mvc.perform(post("/api/v1/console/me/role-view")
                            .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY, StaffRole.SUPPORT))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"support\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("support"))
                    .andExpect(jsonPath("$.screens").value(not(hasItem("trust"))));
            var rows = jdbc.sql("""
                            select action, role, target_type, target_id, merchant_id, after->>'role' as picked
                              from developer.audit_log where actor_id = :u""")
                    .param("u", staff)
                    .query((rs, _) -> rs.getString("action") + "|" + rs.getString("role") + "|"
                            + rs.getString("target_type") + "|" + rs.getString("target_id") + "|"
                            + rs.getString("merchant_id") + "|" + rs.getString("picked"))
                    .list();
            assertThat(rows)
                    .containsExactly("console.role_view_switched|support,trust_safety|staff_role|support|null|support");
        }

        @Test
        void aRoleNotHeld_isRefused() throws Exception {
            mvc.perform(post("/api/v1/console/me/role-view")
                            .with(TestJwt.staff(data.user("Maya"), StaffRole.DISPATCH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"admin\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("role_not_held"))
                    .andExpect(jsonPath("$.detail").value("You don't hold that role."));
        }

        @Test
        void aMissingRole_is422() throws Exception {
            mvc.perform(post("/api/v1/console/me/role-view")
                            .with(TestJwt.staff(data.user("Maya"), StaffRole.DISPATCH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("role"))
                    .andExpect(jsonPath("$.errors[0].message").value("Choose a role."));
        }
    }

    @Nested
    class Enforcement {

        @Test
        void aRoleWithoutTheScreen_isRefused() throws Exception {
            mvc.perform(get("/api/v1/console/trust/flags").with(TestJwt.staff(data.user("Fin"), StaffRole.FINANCE)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"))
                    .andExpect(jsonPath("$.detail").value("Your role can't open this screen."));
            mvc.perform(get("/api/v1/console/registry-reviews")
                            .with(TestJwt.staff(data.user("Ana"), StaffRole.ANALYST)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
        }

        @Test
        void aRoleThatOpensTheScreenButNotTheAction_isRefused() throws Exception {
            // Support opens no finance screen; trust & safety opens trust but a dispatcher can't decide flags.
            mvc.perform(post("/api/v1/console/payments/tax-reconciliations")
                            .with(TestJwt.staff(data.user("Sup"), StaffRole.SUPPORT)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            mvc.perform(post("/api/v1/console/trust/flags/{id}/decision", "01J9ZD3V00000000000000NONE")
                            .with(TestJwt.staff(data.user("Dis"), StaffRole.DISPATCH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"dismissed\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
        }

        @Test
        void theRoleView_narrowsToOneHeldRole() throws Exception {
            var staff = data.user("Lena");
            var token = TestJwt.staff(staff, StaffRole.TRUST_SAFETY, StaffRole.FINANCE);
            mvc.perform(get("/api/v1/console/trust/flags").with(token)).andExpect(status().isOk());
            mvc.perform(get("/api/v1/console/trust/flags").with(token).header(StaffAccess.ROLE_VIEW_HEADER, "finance"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            mvc.perform(get("/api/v1/console/trust/flags").with(token).header(StaffAccess.ROLE_VIEW_HEADER, "admin"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("role_not_held"));
        }

        @Test
        void adminOpensEverything() throws Exception {
            var admin = TestJwt.staff(data.user("Root"), StaffRole.ADMIN);
            mvc.perform(get("/api/v1/console/trust/flags").with(admin)).andExpect(status().isOk());
            mvc.perform(get("/api/v1/console/registry-reviews").with(admin)).andExpect(status().isOk());
        }
    }
}
