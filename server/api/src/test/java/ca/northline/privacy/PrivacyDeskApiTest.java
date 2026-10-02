package ca.northline.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-105: the console's privacy queue — who may see and act (privacy officer, support lead, admin; acr=mfa), recording
 * a request made by email, staff verification, the one extension the law allows, refusals, starting an erasure early,
 * applying corrections, and the audit log with the staff member's roles.
 */
class PrivacyDeskApiTest extends IntegrationTest {

    static final String PATH = "/api/v1/console/privacy-requests";

    @Autowired
    JdbcClient jdbc;

    String officer;
    String person;
    String email;

    @BeforeEach
    void people() {
        officer = data.user("Priya Natarajan");
        person = Ids.next();
        email = "dana-" + person.toLowerCase(java.util.Locale.ROOT) + "@example.ca";
        jdbc.sql("""
                        insert into identity.users (id, first_name, last_name, display_name, email, locale, status)
                        values (:id, 'Dana', 'Kowalski', 'Dana Kowalski', :email, 'fr-CA', 'active')
                        """).param("id", person).param("email", email).update();
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions record(String type, String extra) throws Exception {
        return mvc.perform(json(
                post(PATH).with(TestJwt.staff(officer, StaffRole.PRIVACY)),
                "{\"contact\":\"%s\",\"type\":\"%s\"%s}"
                        .formatted(email.toUpperCase(java.util.Locale.ROOT), type, extra)));
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void onlyThePrivacyOfficerSupportLeadsAndAdminsWithASecondFactor() throws Exception {
        mvc.perform(get(PATH).with(TestJwt.customer(officer))).andExpect(status().isForbidden());
        mvc.perform(get(PATH).with(TestJwt.staffWithoutMfa(officer, StaffRole.PRIVACY)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        mvc.perform(get(PATH).with(TestJwt.staff(officer, StaffRole.SUPPORT))).andExpect(status().isForbidden());
        mvc.perform(get(PATH).with(TestJwt.staff(officer, StaffRole.ANALYST))).andExpect(status().isForbidden());
        for (var role : new StaffRole[] {StaffRole.PRIVACY, StaffRole.SUPPORT_LEAD, StaffRole.ADMIN}) {
            mvc.perform(get(PATH).with(TestJwt.staff(officer, role))).andExpect(status().isOk());
        }
        mvc.perform(json(post(PATH).with(TestJwt.staff(officer, StaffRole.FINANCE)), "{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRequestByEmail_isVerifiedByStaff_extendedOnce_andErasureStartsWhenStaffSay() throws Exception {
        var result = record("erasure", "")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("awaiting_verification"))
                .andExpect(jsonPath("$.channel").value("staff"))
                .andExpect(jsonPath("$.law.code").value("ab_pipa"))
                .andExpect(jsonPath("$.law.responseDays").value(45));
        var id = id(result);
        Instant due =
                Instant.parse(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.dueAt"));

        mvc.perform(get(PATH).with(TestJwt.staff(officer, StaffRole.PRIVACY)))
                .andExpect(jsonPath("$.items[*].id", hasItem(id)));
        mvc.perform(post(PATH + "/{id}/start", id).with(TestJwt.staff(officer, StaffRole.PRIVACY)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_startable"));

        mvc.perform(post(PATH + "/{id}/verify", id).with(TestJwt.staff(officer, StaffRole.PRIVACY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("verified"))
                .andExpect(jsonPath("$.verification").value("staff"));

        var extended = mvc.perform(json(
                        post(PATH + "/{id}/extend", id).with(TestJwt.staff(officer, StaffRole.PRIVACY)),
                        "{\"reason\":\"volume\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extensionReason").value("volume"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Instant to = Instant.parse(JsonPath.read(extended, "$.extendedTo"));
        assertThat(Duration.between(due, to).toDays()).isEqualTo(30);
        mvc.perform(json(
                        post(PATH + "/{id}/extend", id).with(TestJwt.staff(officer, StaffRole.PRIVACY)),
                        "{\"reason\":\"volume\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_extended"));

        mvc.perform(post(PATH + "/{id}/start", id).with(TestJwt.staff(officer, StaffRole.SUPPORT_LEAD)))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("select scheduled_for <= now() from privacy.requests where id = :id")
                        .param("id", id)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(jdbc.sql("""
                        select string_agg(action || ':' || role, ',' order by at, id) from developer.audit_log
                         where target_id = :id
                        """).param("id", id).query(String.class).single())
                .isEqualTo("privacy.request_opened:staff,privacy.request_verified:privacy,"
                        + "privacy.request_extended:privacy,privacy.request_start_requested:support_lead");
    }

    @Test
    void validationMessages_andAnUnknownContact() throws Exception {
        mvc.perform(json(post(PATH).with(TestJwt.staff(officer, StaffRole.PRIVACY)), "{\"type\":\"access\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("contact"))
                .andExpect(jsonPath("$.errors[0].message").value("Enter the person's email or mobile number."));
        mvc.perform(json(
                        post(PATH).with(TestJwt.staff(officer, StaffRole.PRIVACY)),
                        "{\"contact\":\"nobody@example.invalid\",\"type\":\"access\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("No Northline account uses that email or mobile number."));
        var id = id(record("access", ""));
        mvc.perform(json(
                        post(PATH + "/{id}/reject", id).with(TestJwt.staff(officer, StaffRole.PRIVACY)),
                        "{\"decision\":\"because\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose why the request is refused."));
        mvc.perform(json(
                        post(PATH + "/{id}/extend", id).with(TestJwt.staff(officer, StaffRole.PRIVACY)),
                        "{\"reason\":\"\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose why more time is needed."));

        mvc.perform(json(
                        post(PATH + "/{id}/reject", id).with(TestJwt.staff(officer, StaffRole.PRIVACY)),
                        "{\"decision\":\"identity_not_verified\",\"note\":\"No reply to our email\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("rejected"))
                .andExpect(jsonPath("$.decision").value("identity_not_verified"));
        mvc.perform(get(PATH + "?state=closed").with(TestJwt.staff(officer, StaffRole.PRIVACY)))
                .andExpect(jsonPath("$.items[*].id", hasItem(id)));
    }

    @Test
    void aLawWithoutAnExtensionRefusesOne() throws Exception {
        jdbc.sql("""
                        insert into identity.addresses (id, user_id, street, city, province, postal, is_default)
                        values (:id, :u, '1 Rue Principale', 'Gatineau', 'QC', 'J8X 1A1', true)
                        """).param("id", Ids.next()).param("u", person).update();
        var id = id(record("access", "").andExpect(jsonPath("$.law.code").value("qc_law25")));

        mvc.perform(json(
                        post(PATH + "/{id}/extend", id).with(TestJwt.staff(officer, StaffRole.PRIVACY)),
                        "{\"reason\":\"volume\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("no_extension"))
                .andExpect(jsonPath("$.detail").value("The law that applies to this request allows no extension."));
    }

    @Test
    void staffApplyTheCorrectionsTheyAccept() throws Exception {
        var merchant = data.merchant("provider", "Prairie Wrench");
        jdbc.sql("""
                        insert into payments.escrows (id, ref_type, ref_id, merchant_id, amount_cents, state, kind,
                                                      customer_id, customer_name)
                        values (:id, 'booking', :ref, :m, 5000, 'released', 'service', :u, 'D. Kowalsky')
                        """)
                .param("id", Ids.next())
                .param("ref", Ids.next())
                .param("m", merchant)
                .param("u", person)
                .update();
        var id = id(record("correction", ",\"corrections\":[{\"field\":\"receiptName\",\"value\":\"D. Kowalski\"}]"));
        mvc.perform(post(PATH + "/{id}/verify", id).with(TestJwt.staff(officer, StaffRole.PRIVACY)));

        mvc.perform(get(PATH + "/{id}", id).with(TestJwt.staff(officer, StaffRole.PRIVACY)))
                .andExpect(jsonPath("$.corrections[0].value").value("D. Kowalski"));
        mvc.perform(json(
                        post(PATH + "/{id}/corrections", id).with(TestJwt.staff(officer, StaffRole.PRIVACY)),
                        "{\"corrections\":[{\"field\":\"receiptName\",\"value\":\" \"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("corrections[0].value"));
        mvc.perform(json(
                        post(PATH + "/{id}/corrections", id).with(TestJwt.staff(officer, StaffRole.PRIVACY)),
                        "{\"corrections\":[{\"field\":\"receiptName\",\"value\":\"D. Kowalski\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.corrections").isEmpty());

        assertThat(jdbc.sql("select customer_name from payments.escrows where customer_id = :u")
                        .param("u", person)
                        .query(String.class)
                        .single())
                .isEqualTo("D. Kowalski");
        assertThat(jdbc.sql("select sealed_data is null from privacy.requests where id = :id")
                        .param("id", id)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(jdbc.sql("""
                        select count(*) from developer.audit_log
                         where target_id = :id and action = 'privacy.request_correction_applied'
                           and after->>'field' = 'receiptName'
                        """).param("id", id).query(Integer.class).single()).isEqualTo(1);
    }
}
