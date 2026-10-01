package ca.northline.payments;

import static ca.northline.payments.PaymentsFixture.hoursAgo;
import static ca.northline.payments.PaymentsFixture.inHours;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.payments.api.DisputeDecided;
import ca.northline.payments.application.DisputeEvidenceStorage;
import ca.northline.payments.application.PaymentsJobs;
import ca.northline.shared.Ids;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-80: the console's disputes &amp; refunds — the agents' queue (disputes with an agent, refund cases escalated after
 * the seller's 24 h), evidence, decisions that move escrow through the case paths (never an instant refund), the
 * finance co-sign above $500, the audit log, and the role gates.
 */
@Import(PaymentsFixture.class)
@RecordApplicationEvents
class ConsoleDisputesApiTest extends IntegrationTest {

    static final String DESK = "/api/v1/console/disputes";

    @Autowired
    PaymentsFixture fx;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PaymentsJobs jobs;

    @Autowired
    DisputeEvidenceStorage storage;

    @Autowired
    ApplicationEvents events;

    PaymentsFixture.Shop shop;
    String agent;
    String finance;

    @BeforeEach
    void seed() {
        shop = fx.shop("provider", "master");
        agent = data.user("Dev Kaur");
        finance = data.user("Lena Okafor");
    }

    /** A dispute of {@code cents} that went to an agent (the seller contested). */
    String agentDispute(long cents) {
        var escrow = fx.escrow(
                shop.merchantId(), "service", "held", cents, 900, "Pre-purchase", "A. Osei", hoursAgo(90), inHours(2));
        var id = fx.dispute(shop.merchantId(), escrow, cents, PaymentsFixture.caseNumber("DS"));
        jdbc.sql("update payments.disputes set state = 'agent', response = 'Hoses dry at 2:14 pm.' where id = ?")
                .params(id)
                .update();
        return id;
    }

    String escrowOf(String disputeId) {
        return jdbc.sql(
                        "select e.state from payments.escrows e join payments.disputes d on d.ref_id = e.id where d.id = ?")
                .params(disputeId)
                .query(String.class)
                .single();
    }

    ResultActions decide(String kind, String id, String body, StaffRole role) throws Exception {
        return mvc.perform(post(DESK + "/{k}/{id}/decision", kind, id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(TestJwt.staff(agent, role)));
    }

    ResultActions cosign(String decisionId, String body, String who, StaffRole role) throws Exception {
        return mvc.perform(post(DESK + "/decisions/{d}/cosign", decisionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(TestJwt.staff(who, role)));
    }

    List<String> audit(String caseId) {
        return jdbc.sql("select action || ':' || role from developer.audit_log where target_id = ? order by at")
                .params(caseId)
                .query(String.class)
                .list();
    }

    @Test
    void theQueueShowsAgentCases_withEvidenceAndContext() throws Exception {
        var id = agentDispute(16_000);
        var key = "test/evidence/" + Ids.next() + ".pdf";
        storage.put(key, "%PDF-1.7 report".getBytes(StandardCharsets.UTF_8), "application/pdf");
        jdbc.sql("""
                        update payments.disputes set evidence = cast(? as jsonb) where id = ?""")
                .params(
                        "[{\"id\":\"E1\",\"kind\":\"report\",\"name\":\"inspection.pdf\",\"contentType\":\"application/pdf\","
                                + "\"size\":15,\"by\":\"merchant\",\"at\":\"2026-09-06T20:00:00Z\",\"storageKey\":\""
                                + key + "\"}]",
                        id)
                .update();

        mvc.perform(get(DESK).with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.forAgent").isNumber())
                .andExpect(jsonPath("$.items[?(@.row.id == '%s')].row.state".formatted(id))
                        .value("agent"))
                .andExpect(jsonPath("$.items[?(@.row.id == '%s')].businessName".formatted(id))
                        .value("Prairie Wrench"));
        mvc.perform(get(DESK + "/dispute/{id}", id).with(TestJwt.staff(agent, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.row.customerStatement")
                        .value("Report missed a coolant leak the dealer found two days later."))
                .andExpect(jsonPath("$.item.row.sellerStatement").value("Hoses dry at 2:14 pm."))
                .andExpect(jsonPath("$.detail.evidence[0].name").value("inspection.pdf"))
                .andExpect(jsonPath("$.detail.evidence[0].file").value(true));
        mvc.perform(get(DESK + "/dispute/{id}/evidence/E1", id).with(TestJwt.staff(agent, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().string("%PDF-1.7 report"));
    }

    @Test
    void aPartialDecision_refundsThroughTheQueue_andReleasesTheRest() throws Exception {
        var id = agentDispute(16_000);
        decide("dispute", id, "{\"outcome\":\"partial\",\"refundCents\":0}", StaffRole.TRUST_SAFETY)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("A partial refund is more than $0 and less than the amount in escrow."));
        decide("dispute", id, "{\"outcome\":\"maybe\"}", StaffRole.TRUST_SAFETY)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose an outcome."));

        decide(
                        "dispute",
                        id,
                        "{\"outcome\":\"partial\",\"refundCents\":8000,\"note\":\"Half: the leak was borderline.\"}",
                        StaffRole.TRUST_SAFETY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.state").value("decided"))
                .andExpect(jsonPath("$.row.decision.outcome").value("partial"))
                .andExpect(jsonPath("$.row.decision.refundCents").value(8000));
        assertThat(escrowOf(id)).isEqualTo("released");
        // never instant: the refund waits in the approved queue until the job pays it
        assertThat(jdbc.sql("select state from payments.refunds where dispute_id = ?")
                        .params(id)
                        .query(String.class)
                        .single())
                .isEqualTo("approved");
        jobs.payRefundQueue();
        assertThat(jdbc.sql("select state from payments.refunds where dispute_id = ?")
                        .params(id)
                        .query(String.class)
                        .single())
                .isEqualTo("paid");
        assertThat(events.stream(DisputeDecided.class)
                        .filter(e -> e.aggregateId().equals(id)))
                .singleElement()
                .satisfies(e -> assertThat(e.note()).isEqualTo("Half: the leak was borderline."));
        assertThat(audit(id)).containsExactly("disputes.decided:trust_safety");
        decide("dispute", id, "{\"outcome\":\"release\"}", StaffRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_with_agent"));
    }

    @Test
    void goodwillCredit_releasesToTheSeller_andThePlatformPays() throws Exception {
        var id = agentDispute(8_800);
        decide("dispute", id, "{\"outcome\":\"goodwill_credit\",\"refundCents\":2000}", StaffRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.decision.outcome").value("goodwill_credit"));
        assertThat(escrowOf(id)).isEqualTo("released");
        assertThat(jdbc.sql(
                                "select kind || ':' || charged_to || ':' || amount_cents from payments.refunds where dispute_id = ?")
                        .params(id)
                        .query(String.class)
                        .single())
                .isEqualTo("credit:platform:2000");
    }

    @Test
    void aboveFiveHundred_aFinanceCoSignMovesTheMoney() throws Exception {
        var id = agentDispute(64_000);
        var body = mvc.perform(post(DESK + "/dispute/{id}/decision", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"full_refund\",\"note\":\"Understaffed event.\"}")
                        .with(TestJwt.staff(agent, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.state").value("awaiting_cosign"))
                .andExpect(jsonPath("$.row.pending.refundCents").value(64_000))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String decisionId = com.jayway.jsonpath.JsonPath.read(body, "$.row.pending.id");
        assertThat(escrowOf(id)).isEqualTo("disputed"); // nothing moved yet
        decide("dispute", id, "{\"outcome\":\"release\"}", StaffRole.TRUST_SAFETY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("awaiting_cosign"));

        // not the same person, and only a role with `refund`
        cosign(decisionId, "{\"decision\":\"approve\"}", agent, StaffRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("cosign_self"))
                .andExpect(jsonPath("$.detail").value("Another person must co-sign this decision."));
        cosign(decisionId, "{\"decision\":\"approve\"}", finance, StaffRole.TRUST_SAFETY)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
        cosign(decisionId, "{\"decision\":\"yes\"}", finance, StaffRole.FINANCE)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose approve or decline."));

        cosign(decisionId, "{\"decision\":\"approve\",\"note\":\"Checked the quote.\"}", finance, StaffRole.FINANCE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.state").value("decided"))
                .andExpect(jsonPath("$.row.decision.cosignedBy").value(finance));
        assertThat(escrowOf(id)).isEqualTo("refunded");
        assertThat(audit(id)).containsExactly("disputes.decision_awaiting_cosign:admin", "disputes.cosigned:finance");
        cosign(decisionId, "{\"decision\":\"approve\"}", finance, StaffRole.FINANCE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("cosign_closed"));
    }

    @Test
    void aDeclinedCoSign_leavesTheCaseWithTheAgent() throws Exception {
        var id = agentDispute(70_000);
        var body = decide("dispute", id, "{\"outcome\":\"full_refund\"}", StaffRole.ADMIN)
                .andReturn()
                .getResponse()
                .getContentAsString();
        String decisionId = com.jayway.jsonpath.JsonPath.read(body, "$.row.pending.id");
        cosign(decisionId, "{\"decision\":\"decline\",\"note\":\"Partial fits better.\"}", finance, StaffRole.FINANCE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.state").value("agent"));
        decide("dispute", id, "{\"outcome\":\"partial\",\"refundCents\":20000}", StaffRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.state").value("decided"));
    }

    @Test
    void escalatedRefundCases_areApprovedOrReleased() throws Exception {
        var escrow = fx.escrow(
                shop.merchantId(), "goods", "held", 3_100, 1500, "Meat", "R. Diaz", hoursAgo(30), inHours(100));
        var refund = fx.refundCase(shop.merchantId(), escrow, 3_100, PaymentsFixture.caseNumber("RF"));
        jdbc.sql(
                        "update payments.refunds set state = 'agent_review', contest_reason = 'Packed cold at 5:40' where id = ?")
                .params(refund)
                .update();
        mvc.perform(get(DESK + "/refund/{id}", refund).with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(jsonPath("$.item.row.sellerStatement").value("Packed cold at 5:40"))
                .andExpect(jsonPath("$.item.row.state").value("agent"));
        decide("refund", refund, "{\"outcome\":\"partial\",\"refundCents\":1000}", StaffRole.TRUST_SAFETY)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("A refund case is refunded in full or released to the seller."));
        decide("refund", refund, "{\"outcome\":\"full_refund\"}", StaffRole.TRUST_SAFETY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.state").value("approved"));
        assertThat(audit(refund)).containsExactly("disputes.decided:trust_safety");
    }

    @Test
    void rolesAreEnforcedByTheApi() throws Exception {
        var id = agentDispute(5_000);
        var release = "{\"outcome\":\"release\"}";
        for (var role : List.of(StaffRole.DISPATCH, StaffRole.ANALYST)) {
            mvc.perform(get(DESK).with(TestJwt.staff(agent, role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
        }
        // finance and support open the screen but can't decide
        for (var role : List.of(StaffRole.FINANCE, StaffRole.SUPPORT, StaffRole.DISPATCH)) {
            decide("dispute", id, release, role)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
        }
        mvc.perform(get(DESK).with(TestJwt.staffWithoutMfa(agent, StaffRole.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        mvc.perform(post(DESK + "/dispute/{id}/decision", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(release)
                        .header("X-Console-Role", "finance")
                        .with(TestJwt.staff(agent, StaffRole.TRUST_SAFETY, StaffRole.FINANCE)))
                .andExpect(status().isForbidden());
        assertThat(audit(id)).isEmpty();
        assertThat(escrowOf(id)).isEqualTo("disputed");
        mvc.perform(get(DESK + "?market=nowhere").with(TestJwt.staff(agent, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a market from the list."));
    }
}
