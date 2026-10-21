package ca.northline.standing;

import static ca.northline.payments.PaymentsFixture.hoursAgo;
import static ca.northline.payments.PaymentsFixture.inHours;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.payments.PaymentsFixture;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.SettingsFixtures;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-156: a business's standing — consequences recorded as they happen (S-82's staff actions included) with the reason
 * the business sees; appeals once per decision or consequence, with files, taken and decided in the console by someone
 * other than the original decider, audited and emailed; a granted dispute appeal pays the seller; coaching plans; staff
 * lifting consequences; the role gates (owners appeal; trust &amp; safety decide) and the validation messages.
 */
@Import(PaymentsFixture.class)
class StandingApiTest extends IntegrationTest {

    static final String CONSOLE = "/api/v1/console";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PaymentsFixture payments;

    String suspender;
    String reviewer;
    String owner;
    String ownerEmail;
    String technician;
    String business;

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @BeforeEach
    void business() {
        suspender = data.user("Dev Kaur");
        reviewer = data.user("Rae Lindqvist");
        var fx = new SettingsFixtures(jdbc);
        ownerEmail = SettingsFixtures.email("owner");
        owner = fx.person("Bo River", ownerEmail, null, "totp");
        business = data.merchant("provider", "Standing Mechanics");
        jdbc.sql("update merchants.merchants set tier = 'trusted', status = 'active' where id = ?")
                .params(business)
                .update();
        data.member(business, owner, MerchantRole.OWNER);
        technician = data.user("Jas Gill");
        data.member(business, technician, MerchantRole.TECHNICIAN);
    }

    ResultActions standing(String who) throws Exception {
        return mvc.perform(get("/api/v1/merchants/{m}/standing", business).with(TestJwt.member(who)));
    }

    String standingJson() throws Exception {
        return standing(owner)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** Waits until the registry shows an active consequence of {@code kind}; returns its id. */
    String awaitConsequence(String kind) {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> JsonPath.<List<String>>read(standingJson(), "$.active[?(@.kind == '" + kind + "')].id")
                                .size()
                        == 1);
        return JsonPath.<List<String>>read(uncheckedStanding(), "$.active[?(@.kind == '" + kind + "')].id")
                .getFirst();
    }

    String uncheckedStanding() {
        try {
            return standingJson();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    ResultActions appeal(String who, String type, String subject, String statement) throws Exception {
        return mvc.perform(
                json(post("/api/v1/merchants/{m}/standing/appeals", business), """
                        {"subjectType":"%s","subjectId":"%s","statement":"%s"}""".formatted(type, subject, statement))
                        .with(TestJwt.member(who)));
    }

    ResultActions decide(String appealId, String who, StaffRole role, String body) throws Exception {
        return mvc.perform(
                json(post(CONSOLE + "/appeals/{id}/decision", appealId), body).with(TestJwt.staff(who, role)));
    }

    String merchantStatus() {
        return jdbc.sql("select status from merchants.merchants where id = ?")
                .params(business)
                .query(String.class)
                .single();
    }

    long audited(String action, String actor) {
        return jdbc.sql(
                        "select count(*) from developer.audit_log where merchant_id = ? and action = ? and actor_id = ?")
                .params(business, action, actor)
                .query(Long.class)
                .single();
    }

    void awaitEmail(String subject) {
        await().atMost(Duration.ofSeconds(10))
                .until(() ->
                        emails.to(ownerEmail).stream().anyMatch(m -> m.subject().equals(subject)));
    }

    @Test
    void aStaffSuspension_isAConsequence_theOwnerAppealsOnce_andAnotherAgentGrantsIt() throws Exception {
        mvc.perform(json(
                                post(CONSOLE + "/merchants/{id}/suspend", business),
                                "{\"reason\":\"Customers report payments outside Northline.\"}")
                        .with(TestJwt.staff(suspender, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk());
        var consequence = awaitConsequence("suspended");
        standing(owner)
                .andExpect(jsonPath("$.active[0].cause").value("staff"))
                .andExpect(jsonPath("$.active[0].reason").value("Customers report payments outside Northline."))
                .andExpect(jsonPath("$.active[0].appealable").value(true));

        // reading is for every member, MFA required; appealing speaks for the business: owners only
        standing(technician).andExpect(status().isOk());
        mvc.perform(get("/api/v1/merchants/{m}/standing", business).with(TestJwt.memberWithoutMfa(owner)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        mvc.perform(get("/api/v1/merchants/{m}/standing", business).with(TestJwt.member(reviewer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_a_member"));
        appeal(technician, "consequence", consequence, "We never asked anyone to pay outside.")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));

        // the validation messages
        appeal(owner, "consequence", consequence, " ")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("statement"))
                .andExpect(jsonPath("$.errors[0].message").value("Tell us why the decision should change."));
        appeal(owner, "consequence", consequence, "x".repeat(2001))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Keep your appeal under 2,000 characters."));
        appeal(owner, "verdict", consequence, "Please look again.")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("subjectType"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose what you're appealing."));
        appeal(owner, "consequence", Ids.next(), "Please look again.").andExpect(status().isNotFound());

        var body = appeal(owner, "consequence", consequence, "We never asked anyone to pay outside Northline.")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(startsWith("AP-")))
                .andExpect(jsonPath("$.state").value("open"))
                .andExpect(jsonPath("$.subjectKind").value("suspended"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String appealId = JsonPath.read(body, "$.id");
        assertThat(audited("standing.appeal_filed", owner)).isEqualTo(1);
        // once per consequence
        appeal(owner, "consequence", consequence, "Again.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_appealed"));

        // evidence files: photos and PDFs, nothing else
        var uploaded = mvc.perform(post("/api/v1/merchants/{m}/standing/appeals/{a}/files", business, appealId)
                        .contentType(MediaType.APPLICATION_PDF)
                        .header("X-File-Name", "message%20log.pdf")
                        .content("%PDF-1.7 messages".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .with(TestJwt.member(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.files[0].name").value("message log.pdf"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String fileId = JsonPath.read(uploaded, "$.files[0].id");
        mvc.perform(post("/api/v1/merchants/{m}/standing/appeals/{a}/files", business, appealId)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello")
                        .with(TestJwt.member(owner)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Upload a photo (JPG, PNG, HEIC) or a PDF."));
        mvc.perform(get("/api/v1/merchants/{m}/standing/appeals/{a}/files/{f}", business, appealId, fileId)
                        .with(TestJwt.member(technician)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
        var other = data.business(MerchantRole.OWNER);
        mvc.perform(get("/api/v1/merchants/{m}/standing/appeals/{a}/files/{f}", other.merchantId(), appealId, fileId)
                        .with(TestJwt.member(other.userId())))
                .andExpect(status().isNotFound());
        standing(owner)
                .andExpect(jsonPath("$.active[0].appealable").value(false))
                .andExpect(jsonPath("$.active[0].appeal.state").value("open"));

        // the console's queue: trust & safety (support doesn't open the screen)
        mvc.perform(get(CONSOLE + "/appeals").with(TestJwt.staff(reviewer, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.appeal.id == '" + appealId + "')].businessName")
                        .value("Standing Mechanics"));
        mvc.perform(get(CONSOLE + "/appeals").with(TestJwt.staff(reviewer, StaffRole.SUPPORT)))
                .andExpect(status().isForbidden());
        mvc.perform(get(CONSOLE + "/appeals/{id}/files/{f}", appealId, fileId)
                        .with(TestJwt.staff(reviewer, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk());
        mvc.perform(post(CONSOLE + "/appeals/{id}/assign", appealId).with(TestJwt.staff(reviewer, StaffRole.ANALYST)))
                .andExpect(status().isForbidden());
        mvc.perform(post(CONSOLE + "/appeals/{id}/assign", appealId)
                        .with(TestJwt.staff(reviewer, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.appeal.state").value("in_review"))
                .andExpect(jsonPath("$.item.assigneeId").value(reviewer))
                .andExpect(jsonPath("$.consequence.kind").value("suspended"))
                .andExpect(jsonPath("$.originalActor").value(suspender));

        decide(appealId, reviewer, StaffRole.TRUST_SAFETY, "{\"outcome\":\"maybe\",\"reason\":\"x\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose grant or deny."));
        decide(appealId, reviewer, StaffRole.TRUST_SAFETY, "{\"outcome\":\"grant\",\"reason\":\" \"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Give the business a reason."));
        // whoever suspended can't decide the appeal against it
        decide(appealId, suspender, StaffRole.TRUST_SAFETY, "{\"outcome\":\"deny\",\"reason\":\"Stands.\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("appeal_self"));

        decide(
                        appealId,
                        reviewer,
                        StaffRole.TRUST_SAFETY,
                        "{\"outcome\":\"grant\",\"reason\":\"The messages show the customer asked, not you.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.appeal.state").value("granted"))
                .andExpect(jsonPath("$.decidedBy").value(reviewer));
        assertThat(merchantStatus()).isEqualTo("active");
        standing(owner)
                .andExpect(jsonPath("$.active[?(@.kind == 'suspended')]").isEmpty())
                .andExpect(jsonPath("$.past[0].kind").value("suspended"))
                .andExpect(jsonPath("$.past[0].liftedByStaff").value(true))
                .andExpect(jsonPath("$.past[0].liftReason").value(startsWith("Appeal AP-")))
                .andExpect(jsonPath("$.appeals[0].decisionReason")
                        .value("The messages show the customer asked, not you."));
        assertThat(audited("standing.appeal_granted", reviewer)).isEqualTo(1);
        awaitEmail("Your appeal was granted — Standing Mechanics");
        assertThat(emails.to(ownerEmail).stream()
                        .filter(m -> m.subject().startsWith("Your appeal"))
                        .findFirst()
                        .orElseThrow()
                        .text())
                .contains("Reason: The messages show the customer asked, not you.");
        decide(appealId, reviewer, StaffRole.ADMIN, "{\"outcome\":\"deny\",\"reason\":\"No.\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("appeal_decided"));
    }

    @Test
    void denyingAnAppeal_leavesTheConsequence_andStaffCanLiftItLater() throws Exception {
        mvc.perform(json(
                                post(CONSOLE + "/merchants/{id}/search", business),
                                "{\"hidden\":true,\"reason\":\"Listings copied from another business.\"}")
                        .with(TestJwt.staff(suspender, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk());
        var consequence = awaitConsequence("search_hidden");
        String appealId = JsonPath.read(
                appeal(owner, "consequence", consequence, "The photos are ours.")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        decide(
                        appealId,
                        reviewer,
                        StaffRole.ADMIN,
                        "{\"outcome\":\"deny\",\"reason\":\"Same photos as another shop.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.appeal.state").value("denied"));
        assertThat(jdbc.sql("select search_hidden_cause from merchants.merchants where id = ?")
                        .params(business)
                        .query(String.class)
                        .single())
                .isEqualTo("staff");
        awaitEmail("Your appeal was decided — Standing Mechanics");

        // lifting: sellers screen + suspend (merchant success opens it without the action)
        var lift = "{\"reason\":\"The other shop confirmed they copied these photos.\"}";
        mvc.perform(json(post(CONSOLE + "/standing/consequences/{id}/lift", consequence), lift)
                        .with(TestJwt.staff(reviewer, StaffRole.MERCHANT_SUCCESS)))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(CONSOLE + "/standing/consequences/{id}/lift", consequence), "{\"reason\":\"\"}")
                        .with(TestJwt.staff(reviewer, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Give the business a reason."));
        mvc.perform(json(post(CONSOLE + "/standing/consequences/{id}/lift", consequence), lift)
                        .with(TestJwt.staff(reviewer, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        assertThat(jdbc.sql("select search_hidden_at is null from merchants.merchants where id = ?")
                        .params(business)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(audited("standing.consequence_lifted", reviewer)).isEqualTo(1);
        mvc.perform(json(post(CONSOLE + "/standing/consequences/{id}/lift", consequence), lift)
                        .with(TestJwt.staff(reviewer, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("consequence_lifted"));
        mvc.perform(get(CONSOLE + "/standing/{b}", business).with(TestJwt.staff(reviewer, StaffRole.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.past[0].kind").value("search_hidden"));
    }

    @Nested
    class Disputes {

        /** A dispute an agent decided: {@code decision}, {@code refund} back to the customer, {@code daysAgo}. */
        String decided(String decision, long refund, int daysAgo) {
            var escrow = payments.escrow(
                    business, "service", "held", 20_000, 1_000, "Brake job", "A. Osei", hoursAgo(300), inHours(2));
            var id = payments.dispute(business, escrow, 20_000, PaymentsFixture.caseNumber("DS"));
            jdbc.sql("""
                            update payments.disputes set state = 'decided', decision = ?, refund_cents = ?, decided_by = ?,
                                   decided_at = now() - make_interval(days => ?), decision_note = 'The brakes squeal.'
                             where id = ?""").params(decision, refund, suspender, daysAgo, id).update();
            jdbc.sql("""
                            insert into payments.agent_decisions (id, case_kind, case_id, merchant_id, outcome, refund_cents,
                                   decided_by, role, decided_at, state)
                            values (?, 'dispute', ?, ?, ?, ?, ?, 'trust_safety', now() - make_interval(days => ?), 'applied')""")
                    .params(Ids.next(), id, business, decision, refund, suspender, daysAgo)
                    .update();
            return id;
        }

        @Test
        void anAgentsRefundDecision_isAppealableOnce_andGrantingItPaysTheSeller() throws Exception {
            var dispute = decided("full_refund", 20_000, 1);
            standing(owner)
                    .andExpect(jsonPath("$.appealableDisputes[?(@.disputeId == '" + dispute + "')].decision")
                            .value("full_refund"));
            var balance = payments.balance(business);
            String appealId = JsonPath.read(
                    appeal(owner, "dispute", dispute, "The customer drove 400 km after the job; photos attached.")
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.subjectKind").value("full_refund"))
                            .andExpect(jsonPath("$.subjectLabel").value(startsWith("DS-")))
                            .andReturn()
                            .getResponse()
                            .getContentAsString(),
                    "$.id");
            standing(owner)
                    .andExpect(jsonPath("$.appealableDisputes[?(@.disputeId == '" + dispute + "')]")
                            .isEmpty());
            decide(appealId, suspender, StaffRole.TRUST_SAFETY, "{\"outcome\":\"grant\",\"reason\":\"x\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("appeal_self"));
            decide(
                            appealId,
                            reviewer,
                            StaffRole.TRUST_SAFETY,
                            "{\"outcome\":\"grant\",\"reason\":\"The odometer photos show the car was driven.\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.item.appeal.compensationCents").value(18_000))
                    .andExpect(jsonPath("$.dispute.decision").value("full_refund"));
            // what the seller would have been paid on it (after the 10 % fee); the customer keeps the refund
            assertThat(payments.balance(business)).isEqualTo(balance + 18_000);
            assertThat(jdbc.sql("select count(*) from payments.ledger_entries where account = 'appeal_compensation'"
                                    + " and ref_id = ?")
                            .params(appealId)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
            awaitEmail("Your appeal was granted — Standing Mechanics");
            assertThat(emails.to(ownerEmail).stream()
                            .filter(m -> m.subject().startsWith("Your appeal was granted"))
                            .findFirst()
                            .orElseThrow()
                            .text())
                    .contains("Northline paid $180.00 into your balance");
        }

        @Test
        void releasesAndOldDecisions_cantBeAppealed() throws Exception {
            appeal(owner, "dispute", decided("release", 0, 1), "Please look again.")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("not_appealable"));
            appeal(owner, "dispute", decided("partial", 5_000, 20), "Please look again.")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("appeal_window_closed"));
            var other = data.business(MerchantRole.OWNER);
            mvc.perform(json(post("/api/v1/merchants/{m}/standing/appeals", other.merchantId()), """
                                    {"subjectType":"dispute","subjectId":"%s","statement":"Mine?"}""".formatted(
                                            decided("full_refund", 20_000, 1)))
                            .with(TestJwt.member(other.userId())))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class Coaching {

        ResultActions start(String body, StaffRole role) throws Exception {
            return mvc.perform(json(post(CONSOLE + "/standing/{b}/coaching", business), body)
                    .with(TestJwt.staff(reviewer, role)));
        }

        @Test
        void staffAssignAPlan_theBusinessTicksItsTasks_staffCloseIt() throws Exception {
            start("{\"title\":\"Raise your rating\",\"tasks\":[\"Reply to reviews\"]}", StaffRole.MERCHANT_SUCCESS)
                    .andExpect(status().isForbidden());
            start("{\"title\":\" \",\"tasks\":[]}", StaffRole.TRUST_SAFETY)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(
                            jsonPath("$.errors[?(@.field == 'title')].message").value("Give the plan a title."))
                    .andExpect(
                            jsonPath("$.errors[?(@.field == 'tasks')].message").value("Add at least one task."));
            start(
                            "{\"title\":\"Raise your rating\",\"tasks\":[\"a\"],\"dueOn\":\""
                                    + LocalDate.now().minusDays(1) + "\"}",
                            StaffRole.TRUST_SAFETY)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Pick a date after today."));
            var body = start("""
                            {"title":"Raise your rating","note":"Below the floor for three weeks.",
                             "dueOn":"%s","tasks":["Reply to every review within two days","Add completion photos"]}""".formatted(LocalDate.now().plusDays(30)), StaffRole.TRUST_SAFETY)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.tasks.length()").value(2))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String planId = JsonPath.read(body, "$.id");
            String taskId = JsonPath.read(body, "$.tasks[0].id");
            start("{\"title\":\"Another\",\"tasks\":[\"x\"]}", StaffRole.TRUST_SAFETY)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("coaching_active"));
            awaitEmail("A coaching plan for Standing Mechanics");

            standing(technician)
                    .andExpect(jsonPath("$.coaching.title").value("Raise your rating"))
                    .andExpect(jsonPath("$.coaching.tasks[0].done").value(false));
            var tick = "/api/v1/merchants/{m}/standing/coaching/{p}/tasks/{t}";
            mvc.perform(json(put(tick, business, planId, taskId), "{\"done\":true}")
                            .with(TestJwt.member(data.user("Nobody"))))
                    .andExpect(status().isForbidden());
            mvc.perform(json(put(tick, business, planId, taskId), "{}").with(TestJwt.member(technician)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Choose done or not done."));
            mvc.perform(json(put(tick, business, planId, taskId), "{\"done\":true}")
                            .with(TestJwt.member(technician)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tasks[0].done").value(true));

            mvc.perform(json(post(CONSOLE + "/standing/coaching/{p}/close", planId), "{\"completed\":true}")
                            .with(TestJwt.staff(reviewer, StaffRole.TRUST_SAFETY)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("completed"));
            mvc.perform(json(put(tick, business, planId, taskId), "{\"done\":false}")
                            .with(TestJwt.member(technician)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("plan_closed"));
            standing(owner)
                    .andExpect(jsonPath("$.coaching").isEmpty())
                    .andExpect(jsonPath("$.pastCoaching[0].state").value("completed"));
            assertThat(audited("standing.coaching_started", reviewer)).isEqualTo(1);
        }
    }
}
