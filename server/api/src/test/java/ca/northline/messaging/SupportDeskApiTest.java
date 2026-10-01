package ca.northline.messaging;

import static ca.northline.payments.PaymentsFixture.hoursAgo;
import static ca.northline.payments.PaymentsFixture.inHours;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.payments.PaymentsFixture;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.shared.security.StaffAccess;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.TestJwt;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-83: the console's support desk — the agents' queue (chips, KPIs, province / market), the case view with the
 * requester's context, reply / take / escalate (seen by the requester in the case conversation), refund requests that
 * finance — never the requester — decides, the EN/FR macros support leads keep, the audit log and the role gates.
 */
@Import(PaymentsFixture.class)
class SupportDeskApiTest extends MessagingApiTest {

    static final String DESK = "/api/v1/console/support";
    static final String TICKETS = DESK + "/tickets";
    static final String CASES = "/api/v1/merchants/{m}/help/cases";
    static final String CASE = """
            {"topic":"payouts","body":"Why is my payout on hold?","channel":"chat","urgent":%s}""";

    @Autowired
    PaymentsFixture fx;

    String agent;
    String lead;
    String finance;
    String merchantId;
    String ownerId;

    @BeforeEach
    void seed() {
        agent = data.user("Dev Kaur");
        lead = data.user("Priya Natarajan");
        finance = data.user("Lena Okafor");
        merchantId = data.merchant("provider", "Snowline Auto " + Ids.next().substring(20));
        ownerId = data.user("Ravi Owner");
        data.member(merchantId, ownerId, MerchantRole.OWNER);
        province(merchantId, "NU");
    }

    void province(String merchant, String province) {
        jdbc.sql("update merchants.merchants set province = ? where id = ?")
                .params(province, merchant)
                .update();
    }

    String openCase(String merchant, String owner, boolean urgent) throws Exception {
        return json(mvc.perform(postJson(CASES, CASE.formatted(urgent), merchant)
                                .with(TestJwt.member(owner)))
                        .andExpect(status().isCreated()))
                .get("id")
                .asString();
    }

    ResultActions act(String path, String body, String staff, StaffRole... roles) throws Exception {
        return mvc.perform(
                post(path).contentType(MediaType.APPLICATION_JSON).content(body).with(TestJwt.staff(staff, roles)));
    }

    List<String> audit(String targetId) {
        return jdbc.sql("select action || ':' || role from developer.audit_log where target_id = ? order by at")
                .params(targetId)
                .query(String.class)
                .list();
    }

    @Test
    void theQueue_hasChipsAndKpis_scopedByProvince() throws Exception {
        var urgent = openCase(merchantId, ownerId, true);
        var normal = openCase(merchantId, ownerId, false);
        var elsewhere = data.merchant("provider", "Far Away Garage");
        var otherOwner = data.user("Other Owner");
        data.member(elsewhere, otherOwner, MerchantRole.OWNER);
        province(elsewhere, "NT");
        var far = openCase(elsewhere, otherOwner, false);

        mvc.perform(get(TICKETS + "?province=NU").with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", hasItem(urgent)))
                .andExpect(jsonPath("$.items[*].id", hasItem(normal)))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(far))))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].requesterType".formatted(urgent))
                        .value("provider"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].priority".formatted(urgent))
                        .value("urgent"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].state".formatted(normal))
                        .value("new"))
                .andExpect(jsonPath("$.kpis.open").isNumber())
                .andExpect(jsonPath("$.counts.unassigned").isNumber());
        mvc.perform(get(TICKETS + "?province=NU&filter=urgent").with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(jsonPath("$.items[*].id", hasItem(urgent)))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(normal))));
        mvc.perform(get(TICKETS + "?province=NU&filter=mine").with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(normal))));
        act(TICKETS + "/" + normal + "/take", "{}", agent, StaffRole.SUPPORT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.agentName").value("Dev K."))
                .andExpect(jsonPath("$.ticket.state").value("in_progress"));
        mvc.perform(get(TICKETS + "?province=NU&filter=mine").with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(jsonPath("$.items[*].id", hasItem(normal)));
        assertThat(audit(normal)).containsExactly("support.assigned:support");

        mvc.perform(get(TICKETS + "?filter=everything").with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Pick a filter from the list."));
    }

    @Test
    void replies_goToTheRequester_andMoveTheCase() throws Exception {
        var id = openCase(merchantId, ownerId, false);
        mvc.perform(get(TICKETS + "/" + id).with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.portal").value("provider"))
                .andExpect(jsonPath("$.notes[0].by").value("merchant"))
                .andExpect(jsonPath("$.notes[0].body").value("Why is my payout on hold?"));

        act(TICKETS + "/" + id + "/reply", "{\"body\":\"  \"}", agent, StaffRole.SUPPORT)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Write a reply."));
        act(
                        TICKETS + "/" + id + "/reply",
                        "{\"body\":\"Payouts are held for 7 days after a dispute.\",\"macroKey\":\"support.payout_hold\"}",
                        agent,
                        StaffRole.SUPPORT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.state").value("waiting"))
                .andExpect(jsonPath("$.ticket.agentName").value("Dev K."))
                .andExpect(jsonPath("$.notes[1].by").value("agent"))
                .andExpect(jsonPath("$.notes[1].name").value("Dev K."));
        assertThat(jdbc.sql("select first_replied_at is not null from messaging.tickets where id = ?")
                        .params(id)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        // the business sees the reply in Help › its case
        mvc.perform(get(CASES + "/{id}", merchantId, id).with(TestJwt.member(ownerId)))
                .andExpect(jsonPath("$.messages[1].senderRole").value("agent"))
                .andExpect(jsonPath("$.messages[1].body").value("Payouts are held for 7 days after a dispute."));

        act(TICKETS + "/" + id + "/reply", "{\"body\":\"All sorted.\",\"resolve\":true}", agent, StaffRole.SUPPORT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.state").value("resolved"));
        act(TICKETS + "/" + id + "/reply", "{\"body\":\"One more thing\"}", agent, StaffRole.SUPPORT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("case_resolved"));
        assertThat(audit(id)).containsExactly("support.replied:support", "support.replied_resolved:support");
    }

    @Test
    void escalation_toTrustAndSafety_isNotedOnce() throws Exception {
        var id = openCase(merchantId, ownerId, false);
        act(TICKETS + "/" + id + "/escalate", "{\"note\":\"Possible off-platform payment.\"}", agent, StaffRole.SUPPORT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.escalated").value(true))
                .andExpect(jsonPath("$.notes[1].by").value("system"))
                .andExpect(jsonPath("$.notes[1].body")
                        .value("Escalated to trust & safety. Possible off-platform payment."));
        act(TICKETS + "/" + id + "/escalate", "{}", agent, StaffRole.TRUST_SAFETY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_escalated"));
        assertThat(audit(id)).containsExactly("support.escalated:support");
    }

    @Test
    void refundRequests_areDecidedByFinance_neverTheRequester() throws Exception {
        var id = openCase(merchantId, ownerId, false);
        var requests = TICKETS + "/" + id + "/refund-requests";
        act(requests, "{\"amountCents\":0}", agent, StaffRole.SUPPORT)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Enter an amount more than $0."));
        var request = json(act(
                                requests,
                                "{\"amountCents\":2500,\"note\":\"Fee charged twice\"}",
                                agent,
                                StaffRole.SUPPORT)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.refundRequests[0].state").value("pending"))
                        .andExpect(jsonPath("$.refundRequests[0].amountCents").value(2500))
                        .andExpect(
                                jsonPath("$.refundRequests[0].requestedByName").value("Dev K.")))
                .get("refundRequests")
                .get(0)
                .get("id")
                .asString();

        mvc.perform(get(DESK + "/refund-requests?province=NU").with(TestJwt.staff(finance, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].request.id", hasItem(request)))
                .andExpect(jsonPath("$.items[?(@.request.id == '%s')].ticket.id".formatted(request))
                        .value(id));

        var decision = DESK + "/refund-requests/" + request + "/decision";
        act(decision, "{\"decision\":\"approve\"}", agent, StaffRole.SUPPORT)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
        act(decision, "{\"decision\":\"approve\"}", agent, StaffRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("request_self"));
        act(decision, "{\"decision\":\"maybe\"}", finance, StaffRole.FINANCE)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose approve or decline."));
        act(decision, "{\"decision\":\"decline\",\"note\":\"Charged once\"}", finance, StaffRole.FINANCE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundRequests[0].state").value("declined"))
                .andExpect(jsonPath("$.refundRequests[0].decidedBy").value(finance))
                .andExpect(jsonPath("$.refundRequests[0].decisionNote").value("Charged once"));
        act(decision, "{\"decision\":\"approve\"}", lead, StaffRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("request_closed"));
        assertThat(audit(id)).containsExactly("support.refund_requested:support", "support.refund_declined:finance");
    }

    @Test
    void anApprovedRequest_approvesTheCustomersEscalatedRefundCase_paidByTheQueue() throws Exception {
        var shop = fx.shop("seller", "registered");
        var escrow = fx.escrow(
                shop.merchantId(), "goods", "held", 3_100, 1500, "Meat", "R. Diaz", hoursAgo(30), inHours(100));
        var refund = fx.refundCase(shop.merchantId(), escrow, 3_100, PaymentsFixture.caseNumber("RF"));
        jdbc.sql("update payments.refunds set state = 'agent_review' where id = ?")
                .params(refund)
                .update();
        var customer = data.user("Rosa Diaz");
        var ticket = Ids.next();
        jdbc.sql("""
                        insert into messaging.tickets (id, requester_type, requester_id, topic, subject, priority, state,
                                                       lang, context, created_at, updated_at)
                        values (?, 'customer', ?, 'refund', 'Meat arrived warm', 'normal', 'new', 'fr',
                                cast(? as jsonb), now(), now())
                        """)
                .params(ticket, customer, "{\"portal\":\"consumer\",\"refunds\":[\"" + refund + "\"]}")
                .update();
        mvc.perform(get(TICKETS + "/" + ticket).with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(jsonPath("$.ticket.requesterType").value("customer"))
                .andExpect(jsonPath("$.ticket.requesterName").value("Rosa D."))
                .andExpect(jsonPath("$.ticket.lang").value("fr"));
        var request = json(act(
                                TICKETS + "/" + ticket + "/refund-requests",
                                "{\"amountCents\":3100}",
                                agent,
                                StaffRole.SUPPORT)
                        .andExpect(status().isOk()))
                .get("refundRequests")
                .get(0)
                .get("id")
                .asString();
        act(
                        DESK + "/refund-requests/" + request + "/decision",
                        "{\"decision\":\"approve\"}",
                        finance,
                        StaffRole.FINANCE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundRequests[0].state").value("approved"));
        // approved, not paid: the refund queue pays it (never an instant refund)
        assertThat(jdbc.sql("select state from payments.refunds where id = ?")
                        .params(refund)
                        .query(String.class)
                        .single())
                .isEqualTo("approved");
        assertThat(jdbc.sql("select after::text from developer.audit_log where target_id = ? and action = ?")
                        .params(ticket, "support.refund_approved")
                        .query(String.class)
                        .single())
                .contains(refund);
    }

    @Test
    void macros_areKeptBySupportLeads_inEnglishAndFrench() throws Exception {
        mvc.perform(get(DESK + "/macros").with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.key == 'support.payout_hold')].title.fr")
                        .value("Retenue de versement — explication de la politique"));
        var key = "support.test-" + Ids.next().substring(20).toLowerCase(java.util.Locale.ROOT);
        var macro = "{\"key\":\"" + key + "\",\"title\":{\"en\":\"Thanks\",\"fr\":\"Merci\"},"
                + "\"body\":{\"en\":\"Thanks for writing.\",\"fr\":\"Merci de nous avoir écrit.\"}}";
        act(DESK + "/macros", macro, agent, StaffRole.SUPPORT)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
        act(
                        DESK + "/macros",
                        "{\"key\":\"Bad Key\",\"title\":{\"en\":\"x\"},\"body\":{}}",
                        lead,
                        StaffRole.SUPPORT_LEAD)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'key')].message")
                        .value("Use lower-case letters, digits, dots and dashes for the key."))
                .andExpect(jsonPath("$.errors[?(@.field == 'title')].message")
                        .value("Give the macro a title in English and French."))
                .andExpect(jsonPath("$.errors[?(@.field == 'body')].message")
                        .value("Write the macro in English and French."));
        var id = json(act(DESK + "/macros", macro, lead, StaffRole.SUPPORT_LEAD)
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.key").value(key)))
                .get("id")
                .asString();
        act(DESK + "/macros", macro, lead, StaffRole.SUPPORT_LEAD)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("That key is already used."));
        mvc.perform(put(DESK + "/macros/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(macro.replace("Merci de nous avoir écrit.", "Merci de votre message."))
                        .with(TestJwt.staff(lead, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.fr").value("Merci de votre message."));
        mvc.perform(delete(DESK + "/macros/" + id).with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(status().isForbidden());
        mvc.perform(delete(DESK + "/macros/" + id).with(TestJwt.staff(lead, StaffRole.SUPPORT_LEAD)))
                .andExpect(status().isNoContent());
        mvc.perform(get(DESK + "/macros").with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(id))));
        assertThat(audit(id))
                .containsExactly(
                        "support.macro_created:support_lead",
                        "support.macro_updated:admin",
                        "support.macro_deleted:support_lead");
    }

    @Test
    void rolesAreEnforcedByTheApi() throws Exception {
        var id = openCase(merchantId, ownerId, false);
        for (var role : List.of(StaffRole.ANALYST, StaffRole.FINANCE)) {
            mvc.perform(get(TICKETS).with(TestJwt.staff(agent, role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
        }
        // dispatch opens the desk (read only) — every case action needs `support`
        mvc.perform(get(TICKETS + "/" + id).with(TestJwt.staff(agent, StaffRole.DISPATCH)))
                .andExpect(status().isOk());
        for (var action : List.of("reply", "take", "escalate", "refund-requests")) {
            act(TICKETS + "/" + id + "/" + action, "{\"body\":\"Hi\",\"amountCents\":100}", agent, StaffRole.DISPATCH)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
        }
        mvc.perform(get(DESK + "/refund-requests").with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(status().isForbidden());
        // switching the role view to support narrows an admin to support's actions
        mvc.perform(post(DESK + "/macros")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"support.x\",\"title\":{},\"body\":{}}")
                        .header(StaffAccess.ROLE_VIEW_HEADER, "support")
                        .with(TestJwt.staff(lead, StaffRole.ADMIN, StaffRole.SUPPORT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
        mvc.perform(get(TICKETS).with(TestJwt.staffWithoutMfa(agent, StaffRole.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        assertThat(audit(id)).isEmpty();
    }
}
