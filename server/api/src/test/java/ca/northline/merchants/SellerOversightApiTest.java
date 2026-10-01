package ca.northline.merchants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.email.EmailMessage;
import ca.northline.merchants.api.MerchantReinstated;
import ca.northline.merchants.api.MerchantSuspended;
import ca.northline.merchants.api.MerchantTierChanged;
import ca.northline.merchants.api.ReverificationRequired;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.SettingsFixtures;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-82: oversight actions on a business from the console's seller detail — suspend, reinstate, require re-verification,
 * change tier — with the reason, the merchants trail, the platform audit log, the event and the owners' email; and the
 * role gates (suspend for status and tier, verify for re-verification).
 */
@RecordApplicationEvents
class SellerOversightApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    String staff;
    String business;
    String ownerEmail;
    String insurance;
    String kyc;

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @BeforeEach
    void business() {
        staff = data.user("Dev Kaur");
        var fx = new SettingsFixtures(jdbc);
        ownerEmail = SettingsFixtures.email("owner");
        var owner = fx.person("Bo River", ownerEmail, null, "totp");
        business = data.merchant("provider", "Bow River Mechanics");
        jdbc.sql("update merchants.merchants set tier = 'trusted', status = 'active' where id = ?")
                .params(business)
                .update();
        data.member(business, owner, MerchantRole.OWNER);
        insurance = fx.verification(
                business, "insurance", null, null, "verified", Instant.now().plus(Duration.ofDays(21)), null);
        kyc = fx.verification(business, "kyc", null, null, "verified", null, null);
    }

    String merchantStatus() {
        return jdbc.sql("select status from merchants.merchants where id = ?")
                .params(business)
                .query(String.class)
                .single();
    }

    long audited(String action) {
        return jdbc.sql("""
                        select count(*) from developer.audit_log
                         where merchant_id = ? and action = ? and actor_id = ? and target_id = ?""")
                .params(business, action, staff, business)
                .query(Long.class)
                .single();
    }

    List<EmailMessage> awaitEmails(int n) {
        await().atMost(Duration.ofSeconds(10)).until(() -> emails.to(ownerEmail).size() >= n);
        return emails.to(ownerEmail);
    }

    @Test
    void suspendAndReinstateAreAuditedAnnouncedAndEmailed() throws Exception {
        mvc.perform(json(
                                post("/api/v1/console/merchants/{id}/suspend", business),
                                "{\"reason\":\"Off-platform payments, 3rd warning\"}")
                        .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("suspended"))
                .andExpect(jsonPath("$.reason").value("Off-platform payments, 3rd warning"))
                .andExpect(jsonPath("$.detail.from").value("active"))
                .andExpect(jsonPath("$.actorRole").value("trust_safety"));
        assertThat(merchantStatus()).isEqualTo("suspended");
        assertThat(audited("merchant.suspended")).isEqualTo(1);
        assertThat(events.stream(MerchantSuspended.class)
                        .filter(e -> e.aggregateId().equals(business)))
                .hasSize(1);
        var mail = awaitEmails(1).getFirst();
        assertThat(mail.subject()).isEqualTo("Bow River Mechanics is suspended on Northline");
        assertThat(mail.text()).contains("Reason: Off-platform payments, 3rd warning");

        // suspending again is a conflict
        mvc.perform(json(post("/api/v1/console/merchants/{id}/suspend", business), "{\"reason\":\"again\"}")
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_active"));

        mvc.perform(json(post("/api/v1/console/merchants/{id}/reinstate", business), "{\"reason\":\"Appeal accepted\"}")
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("reinstated"));
        assertThat(merchantStatus()).isEqualTo("active");
        assertThat(audited("merchant.reinstated")).isEqualTo(1);
        assertThat(events.stream(MerchantReinstated.class)
                        .filter(e -> e.aggregateId().equals(business)))
                .hasSize(1);
        assertThat(awaitEmails(2).stream().map(EmailMessage::subject))
                .contains("Bow River Mechanics is back on Northline");
        mvc.perform(json(post("/api/v1/console/merchants/{id}/reinstate", business), "{\"reason\":\"x\"}")
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_suspended"));
        assertThat(jdbc.sql("select count(*) from merchants.oversight_actions where merchant_id = ?")
                        .params(business)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void requireReverificationExpiresTheCheckAndTellsTheOwner() throws Exception {
        mvc.perform(json(
                                post("/api/v1/console/merchants/{id}/reverification", business),
                                "{\"verificationId\":\"%s\",\"reason\":\"The certificate looks altered\"}"
                                        .formatted(insurance))
                        .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("reverification_required"))
                .andExpect(jsonPath("$.detail.checkType").value("insurance"));
        assertThat(jdbc.sql("select status from merchants.verifications where id = ?")
                        .params(insurance)
                        .query(String.class)
                        .single())
                .isEqualTo("expired");
        assertThat(audited("merchant.reverification_required")).isEqualTo(1);
        assertThat(events.stream(ReverificationRequired.class)
                        .filter(e -> e.aggregateId().equals(business)))
                .singleElement()
                .satisfies(e -> assertThat(e.checkType()).isEqualTo("insurance"));
        var mail = awaitEmails(1).getFirst();
        assertThat(mail.subject()).isEqualTo("Action needed: verify a document of Bow River Mechanics again");
        assertThat(mail.text()).contains("the insurance certificate").contains("/compliance");

        // an expired check (now) or the owners' identity can't be asked for again
        for (var id : List.of(insurance, kyc)) {
            mvc.perform(json(
                                    post("/api/v1/console/merchants/{id}/reverification", business),
                                    "{\"verificationId\":\"%s\",\"reason\":\"x\"}".formatted(id))
                            .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("not_verifiable"));
        }
        mvc.perform(json(
                                post("/api/v1/console/merchants/{id}/reverification", business),
                                "{\"verificationId\":\"%s\",\"reason\":\"x\"}".formatted(Ids.next()))
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void changeTierWithAReason() throws Exception {
        mvc.perform(json(
                                post("/api/v1/console/merchants/{id}/tier", business),
                                "{\"tier\":\"registered\",\"reason\":\"Quality below the floor three weeks\"}")
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.from").value("trusted"))
                .andExpect(jsonPath("$.detail.to").value("registered"));
        assertThat(jdbc.sql("select tier from merchants.merchants where id = ?")
                        .params(business)
                        .query(String.class)
                        .single())
                .isEqualTo("registered");
        assertThat(events.stream(MerchantTierChanged.class)
                        .filter(e -> e.aggregateId().equals(business)))
                .singleElement()
                .satisfies(e -> assertThat(e.toTier()).isEqualTo("registered"));
        assertThat(awaitEmails(1).getFirst().text()).contains("from Trusted to Registered");
        mvc.perform(json(
                                post("/api/v1/console/merchants/{id}/tier", business),
                                "{\"tier\":\"registered\",\"reason\":\"x\"}")
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("same_tier"));
    }

    @Test
    void validationMessages() throws Exception {
        mvc.perform(json(post("/api/v1/console/merchants/{id}/suspend", business), "{\"reason\":\"  \"}")
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].message").value("Give the reason the business will see."));
        mvc.perform(json(
                                post("/api/v1/console/merchants/{id}/suspend", business),
                                "{\"reason\":\"%s\"}".formatted("x".repeat(501)))
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Keep the reason under 500 characters."));
        mvc.perform(json(post("/api/v1/console/merchants/{id}/tier", business), "{\"tier\":\"gold\",\"reason\":\"x\"}")
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("tier"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose Registered, Trusted or Master."));
        mvc.perform(json(post("/api/v1/console/merchants/{id}/reverification", business), "{\"reason\":\"x\"}")
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose the check to verify again."));
        mvc.perform(json(post("/api/v1/console/merchants/{id}/suspend", Ids.next()), "{\"reason\":\"x\"}")
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void roleGates() throws Exception {
        // support opens sellers but may not suspend; dispatch, finance and analysts don't open sellers
        for (var role : new StaffRole[] {StaffRole.SUPPORT, StaffRole.DISPATCH, StaffRole.FINANCE, StaffRole.ANALYST}) {
            for (var path : List.of("suspend", "reinstate")) {
                mvc.perform(json(post("/api/v1/console/merchants/{id}/" + path, business), "{\"reason\":\"x\"}")
                                .with(TestJwt.staff(staff, role)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("insufficient_role"));
            }
            mvc.perform(json(
                                    post("/api/v1/console/merchants/{id}/tier", business),
                                    "{\"tier\":\"master\",\"reason\":\"x\"}")
                            .with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden());
            mvc.perform(json(
                                    post("/api/v1/console/merchants/{id}/reverification", business),
                                    "{\"verificationId\":\"%s\",\"reason\":\"x\"}".formatted(insurance))
                            .with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(json(post("/api/v1/console/merchants/{id}/suspend", business), "{\"reason\":\"x\"}")
                        .with(TestJwt.staffWithoutMfa(staff, StaffRole.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        mvc.perform(json(post("/api/v1/console/merchants/{id}/suspend", business), "{\"reason\":\"x\"}")
                        .with(TestJwt.member(staff)))
                .andExpect(status().isForbidden());
        assertThat(merchantStatus()).isEqualTo("active");
        assertThat(audited("merchant.suspended")).isZero();
    }
}
