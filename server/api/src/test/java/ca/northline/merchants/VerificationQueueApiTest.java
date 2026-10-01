package ca.northline.merchants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.api.ApplicationDecided;
import ca.northline.merchants.api.MerchantApproved;
import ca.northline.merchants.application.DevIdentityOutcomes;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-79: the console's verification queue — submitted applications with their automated checks, the manual reviews
 * behind them, approve / request info with their side effects (status, checklist, decision row, audit log, events,
 * owners' email), and the role gates (admin and trust &amp; safety open it; deciding needs {@code verify}).
 */
@RecordApplicationEvents
class VerificationQueueApiTest extends IntegrationTest {

    static final String QUEUE = "/api/v1/console/verification/applications";
    private static volatile boolean seeded;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DevIdentityOutcomes identity;

    @Autowired
    ApplicationEvents events;

    OnboardingFlow flow;
    String owner;
    String ownerEmail;
    String agent;

    @BeforeEach
    void setUp() {
        if (!seeded) {
            new CategorySeeder(dataSource).seed();
            seeded = true;
        }
        flow = new OnboardingFlow(mvc, dataSource, identity);
        owner = data.user("Amara Okafor");
        ownerEmail = "owner-" + owner.toLowerCase(java.util.Locale.ROOT) + "@example.test";
        jdbc.sql("update identity.users set email = ? where id = ?")
                .params(ownerEmail, owner)
                .update();
        agent = data.user("Dev Kaur");
    }

    /** A submitted sole-proprietor bakery: every check passes automatically or waits for a human. */
    String submittedBakery(String name) throws Exception {
        var id = flow.start(owner, "seller");
        flow.business(id, owner, OnboardingFlow.soleBusiness(name, "shop.food-and-grocery.bakery"))
                .andExpect(status().isOk());
        flow.completeAll(id, owner);
        flow.submit(id, owner).andExpect(status().isOk());
        return id;
    }

    /** A submitted corporation with an AMVIC licence, which only an agent can check (open registry review). */
    String submittedCorporation() throws Exception {
        var id = flow.start(owner, "provider");
        var doc = flow.upload(id, owner, "legal");
        flow.business(id, owner, OnboardingFlow.corpBusiness(doc, OnboardingFlow.randomBn()))
                .andExpect(status().isOk());
        flow.completeAll(id, owner);
        flow.submit(id, owner).andExpect(status().isOk());
        return id;
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    ResultActions decide(String id, String body, StaffRole role) throws Exception {
        return mvc.perform(json(post(QUEUE + "/{id}/decision", id), body).with(TestJwt.staff(agent, role)));
    }

    List<Map<String, Object>> audit(String merchantId) {
        return jdbc.sql("""
                        select action, actor_id, role, target_type, after::text as after from developer.audit_log
                         where merchant_id = ? and action like 'verification.%' order by at""").params(merchantId).query().listOfRows();
    }

    @Test
    void theQueueListsSubmittedApplications_withTheirChecksRiskAndRegion() throws Exception {
        var id = submittedBakery("Glenmore Crumb");
        var body = mvc.perform(get(QUEUE + "?province=AB").with(TestJwt.staff(agent, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Map<String, Object>> rows = JsonPath.read(body, "$.items[?(@.merchantId == '%s')]".formatted(id));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row)
                    .containsEntry("businessName", "Glenmore Crumb")
                    .containsEntry("legalName", "Amara Okafor")
                    .containsEntry("type", "seller")
                    .containsEntry("province", "AB")
                    .containsEntry("status", "pending")
                    .containsEntry("risk", "low")
                    .containsEntry("categories", List.of("Bakery"));
            assertThat(row.get("submittedAt")).isNotNull();
            assertThat(row.get("decision")).isNull();
        });
        List<String> states = JsonPath.read(body, "$.items[?(@.merchantId == '%s')].checks[*].state".formatted(id));
        assertThat(states).isNotEmpty().allMatch(s -> s.equals("passed") || s.equals("waiting"));

        // another province's queue doesn't show it; an unknown place is a 422
        mvc.perform(get(QUEUE + "?province=BC").with(TestJwt.staff(agent, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.merchantId == '%s')]".formatted(id))
                        .isEmpty());
        mvc.perform(get(QUEUE + "?province=ZZ").with(TestJwt.staff(agent, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("province"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose a province from the list."));
    }

    @Test
    void approve_activatesTheBusiness_logsIt_andEmailsTheOwner() throws Exception {
        var id = submittedBakery("Bow Crumb");

        decide(id, "{\"decision\":\"approve\",\"note\":\"Welcome aboard\"}", StaffRole.TRUST_SAFETY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.status").value("active"))
                .andExpect(jsonPath("$.application.decision").value("approved"))
                .andExpect(jsonPath("$.decisions[0].decision").value("approved"))
                .andExpect(jsonPath("$.decisions[0].decidedBy").value(agent))
                .andExpect(jsonPath(
                        "$.application.checks[*].state",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("passed"))));

        assertThat(audit(id)).singleElement().satisfies(a -> {
            assertThat(a)
                    .containsEntry("action", "verification.application_approved")
                    .containsEntry("actor_id", agent)
                    .containsEntry("role", "trust_safety")
                    .containsEntry("target_type", "merchant");
            assertThat((String) a.get("after"))
                    .contains("\"status\": \"active\"")
                    .contains("registered");
        });
        assertThat(events.stream(MerchantApproved.class))
                .anyMatch(e -> e.aggregateId().equals(id));
        assertThat(events.stream(ApplicationDecided.class)
                        .filter(e -> e.aggregateId().equals(id)))
                .singleElement()
                .satisfies(e -> assertThat(e.decision()).isEqualTo("approved"));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !emails.to(ownerEmail).isEmpty());
        var mail = emails.to(ownerEmail).getFirst();
        assertThat(mail.subject()).isEqualTo("Bow Crumb is approved on Northline");
        assertThat(mail.text())
                .contains("Note from the Northline team: Welcome aboard")
                .contains("/b/" + id);

        // decided: it stays in the queue as approved, and can't be decided again
        mvc.perform(get(QUEUE).with(TestJwt.staff(agent, StaffRole.ADMIN)))
                .andExpect(jsonPath("$.items[?(@.merchantId == '%s')].decision".formatted(id))
                        .value("approved"));
        decide(id, "{\"decision\":\"approve\"}", StaffRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_pending"));
    }

    @Test
    void requestInfo_sendsTheApplicationBack_withTheChecksToRedo() throws Exception {
        var id = submittedBakery("Kensington Crumb");
        var redo = "returns_policy";

        decide(id, "{\"decision\":\"request_info\",\"note\":\"Fix it\"}", StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("checkKeys"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose what the business needs to fix."));
        decide(id, "{\"decision\":\"request_info\",\"checkKeys\":[\"nope\"],\"note\":\"Fix it\"}", StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Pick checks from this application."));
        decide(id, "{\"decision\":\"request_info\",\"checkKeys\":[\"%s\"]}".formatted(redo), StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("note"))
                .andExpect(jsonPath("$.errors[0].message").value("Tell the business what to fix."));
        decide(id, "{\"decision\":\"maybe\"}", StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose approve or request info."));
        decide(id, "{\"decision\":\"approve\",\"note\":\"%s\"}".formatted("x".repeat(501)), StaffRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("At most 500 characters."));
        assertThat(audit(id)).isEmpty();

        decide(
                        id,
                        "{\"decision\":\"request_info\",\"checkKeys\":[\"%s\"],\"note\":\"Upload all pages.\"}"
                                .formatted(redo),
                        StaffRole.TRUST_SAFETY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.status").value("applicant"))
                .andExpect(jsonPath("$.application.decision").value("info_requested"))
                .andExpect(jsonPath("$.decisions[0].checkKeys[0]").value(redo))
                .andExpect(jsonPath("$.decisions[0].note").value("Upload all pages."));

        mvc.perform(get("/api/v1/merchants/{id}/onboarding", id).with(TestJwt.member(owner)))
                .andExpect(jsonPath("$.status").value("applicant"))
                .andExpect(jsonPath("$.step").value("verification"))
                .andExpect(jsonPath("$.checklist[?(@.key == '%s')].status".formatted(redo))
                        .value("rejected"));
        assertThat(audit(id))
                .singleElement()
                .satisfies(a -> assertThat(a)
                        .containsEntry("action", "verification.info_requested")
                        .containsEntry("role", "trust_safety"));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !emails.to(ownerEmail).isEmpty());
        var mail = emails.to(ownerEmail).getFirst();
        assertThat(mail.subject()).isEqualTo("Action needed: Kensington Crumb’s application to Northline");
        assertThat(mail.text())
                .contains("Note from the Northline team: Upload all pages.")
                .contains("/onboarding/verification?m=" + id);

        // the owner fixes it and submits again: back in the queue as pending
        List<String> redoId =
                JsonPath.read(flow.onboarding(id, owner), "$.checklist[?(@.key == '%s')].id".formatted(redo));
        flow.complete(id, owner, redoId.getFirst(), "{\"choice\":\"standard\"}").andExpect(status().isOk());
        flow.submit(id, owner).andExpect(status().isOk());
        mvc.perform(get(QUEUE).with(TestJwt.staff(agent, StaffRole.ADMIN)))
                .andExpect(jsonPath("$.items[?(@.merchantId == '%s')].status".formatted(id))
                        .value("pending"));
    }

    @Test
    void approveWaitsForOpenReviews_andRegistryDecisionsAreLogged() throws Exception {
        var id = submittedCorporation();
        var detail = mvc.perform(get(QUEUE + "/{id}", id).with(TestJwt.staff(agent, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.risk").value("high"))
                .andExpect(jsonPath("$.application.checks[?(@.key == 'licence:AMVIC')].state")
                        .value("review"))
                .andExpect(jsonPath("$.owners.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> open = JsonPath.read(detail, "$.registryReviews[?(@.reviewState == 'open')].id");
        assertThat(open).isNotEmpty();

        decide(id, "{\"decision\":\"approve\"}", StaffRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("reviews_open"));

        for (var review : open) {
            mvc.perform(json(
                                    post("/api/v1/console/registry-reviews/{r}/decision", review),
                                    "{\"decision\":\"approve\"}")
                            .with(TestJwt.staff(agent, StaffRole.TRUST_SAFETY)))
                    .andExpect(status().isOk());
        }
        assertThat(audit(id)).extracting(a -> a.get("action")).containsOnly("verification.registry_approved");

        decide(id, "{\"decision\":\"approve\"}", StaffRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.status").value("active"));
    }

    @Test
    void identityMismatches_areDecidedByAnAgent() throws Exception {
        var id = flow.start(owner, "seller");
        flow.business(id, owner, OnboardingFlow.soleBusiness("Crumb & Co", "shop.food-and-grocery.bakery"))
                .andExpect(status().isOk());
        flow.verifyOwners(id, owner, "name_mismatch");
        flow.completeAll(id, owner);
        flow.submit(id, owner).andExpect(status().isOk());

        var detail = mvc.perform(get(QUEUE + "/{id}", id).with(TestJwt.staff(agent, StaffRole.ADMIN)))
                .andExpect(jsonPath("$.application.checks[?(@.key == 'kyc')].state")
                        .value("review"))
                .andExpect(jsonPath("$.owners[0].status").value("review"))
                .andExpect(jsonPath("$.owners[0].nameMatch").value("mismatch"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String checkId = JsonPath.read(detail, "$.owners[0].checkId");
        decide(id, "{\"decision\":\"approve\"}", StaffRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("reviews_open"));

        var path = QUEUE + "/{id}/identity-reviews/{c}/decision";
        mvc.perform(json(post(path, id, checkId), "{\"decision\":\"later\"}")
                        .with(TestJwt.staff(agent, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose approve or reject."));
        mvc.perform(json(post(path, id, checkId), "{\"decision\":\"approve\",\"note\":\"Married name; ID matches\"}")
                        .with(TestJwt.staff(agent, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owners[0].status").value("verified"))
                .andExpect(jsonPath("$.owners[0].reviewNote").value("Married name; ID matches"))
                .andExpect(jsonPath("$.application.checks[?(@.key == 'kyc')].status")
                        .value("verified"));
        mvc.perform(json(post(path, id, checkId), "{\"decision\":\"reject\"}")
                        .with(TestJwt.staff(agent, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("review_closed"));
        assertThat(audit(id)).extracting(a -> a.get("action")).containsExactly("verification.identity_approved");

        decide(id, "{\"decision\":\"approve\"}", StaffRole.ADMIN).andExpect(status().isOk());
    }

    @Test
    void rolesAreEnforcedByTheApi() throws Exception {
        var id = submittedBakery("Role Crumb");
        var approve = "{\"decision\":\"approve\"}";
        // roles that don't open the screen
        for (var role : List.of(StaffRole.SUPPORT, StaffRole.FINANCE, StaffRole.DISPATCH, StaffRole.ANALYST)) {
            mvc.perform(get(QUEUE).with(TestJwt.staff(agent, role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            decide(id, approve, role).andExpect(status().isForbidden());
            mvc.perform(get(QUEUE + "/{id}", id).with(TestJwt.staff(agent, role)))
                    .andExpect(status().isForbidden());
        }
        // a second factor, staff, and the role view
        mvc.perform(get(QUEUE).with(TestJwt.staffWithoutMfa(agent, StaffRole.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        mvc.perform(json(post(QUEUE + "/{id}/decision", id), approve).with(TestJwt.member(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(QUEUE + "/{id}/decision", id), approve)
                        .header("X-Console-Role", "support")
                        .with(TestJwt.staff(agent, StaffRole.TRUST_SAFETY, StaffRole.SUPPORT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
        assertThat(audit(id)).isEmpty();
        mvc.perform(get(QUEUE + "/{id}", "01J9ZD3V0000000000000NOPE1").with(TestJwt.staff(agent, StaffRole.ADMIN)))
                .andExpect(status().isNotFound());
    }
}
