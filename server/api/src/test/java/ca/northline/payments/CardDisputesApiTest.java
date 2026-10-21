package ca.northline.payments;

import static ca.northline.payments.PaymentsFixture.hoursAgo;
import static ca.northline.payments.PaymentsFixture.inHours;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.payments.application.DisputeEvidenceStorage;
import ca.northline.shared.Ids;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-156: card disputes in the console — the issuer's deadline and Stripe's fee, the evidence draft (Stripe's fields,
 * the case's files), one submission through {@code DisputeEvidenceGateway} (the local fake under {@code test}), a
 * refusal kept with its error, the audit log and the role gates (agents with {@code decide}).
 */
@Import(PaymentsFixture.class)
class CardDisputesApiTest extends IntegrationTest {

    static final String DESK = "/api/v1/console/card-disputes";

    @Autowired
    PaymentsFixture fx;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DisputeEvidenceStorage storage;

    PaymentsFixture.Shop shop;
    String agent;

    @BeforeEach
    void seed() {
        shop = fx.shop("provider", "master");
        agent = data.user("Dev Kaur");
    }

    /** A card dispute opened by Stripe with one stored file of the business's ({@code E1}). */
    String chargeback(String stripeDispute) {
        var escrow = fx.escrow(
                shop.merchantId(), "service", "held", 16_000, 900, "Brake job", "A. Osei", hoursAgo(90), inHours(2));
        var id = fx.dispute(shop.merchantId(), escrow, 16_000, PaymentsFixture.caseNumber("DS"));
        var key = "test/evidence/" + Ids.next() + ".pdf";
        storage.put(key, "%PDF-1.7 invoice".getBytes(StandardCharsets.UTF_8), "application/pdf");
        jdbc.sql("""
                        update payments.disputes set stripe_dispute = ?, stripe_status = 'needs_response',
                               stripe_reason = 'product_not_received', stripe_evidence_due_by = now() + interval '9 days',
                               response = 'The job was done on Sep 4; the invoice is signed.',
                               evidence = cast(? as jsonb)
                         where id = ?""")
                .params(
                        stripeDispute,
                        "[{\"id\":\"E1\",\"kind\":\"document\",\"name\":\"invoice.pdf\",\"contentType\":\"application/pdf\","
                                + "\"size\":16,\"by\":\"merchant\",\"at\":\"2026-09-06T20:00:00Z\",\"storageKey\":\""
                                + key + "\"}]",
                        id)
                .update();
        return id;
    }

    ResultActions save(String id, String body, StaffRole role) throws Exception {
        return mvc.perform(put(DESK + "/{id}/evidence", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(TestJwt.staff(agent, role)));
    }

    ResultActions submit(String id, StaffRole role) throws Exception {
        return mvc.perform(post(DESK + "/{id}/evidence/submit", id).with(TestJwt.staff(agent, role)));
    }

    @Test
    void anAgentAssemblesTheEvidence_andSubmitsItToStripeOnce() throws Exception {
        var id = chargeback("dp_" + Ids.next());
        mvc.perform(get(DESK).with(TestJwt.staff(agent, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.row.id == '" + id + "')].row.dueBy")
                        .isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.row.id == '" + id + "')].businessName")
                        .value("Prairie Wrench"));
        mvc.perform(get(DESK + "/{id}", id).with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.merchantResponse")
                        .value("The job was done on Sep 4; the invoice is signed."))
                .andExpect(jsonPath("$.detail.caseEvidence[0].id").value("E1"));

        // finance and support read it; agents (decide) change it
        save(id, "{\"fields\":{}}", StaffRole.FINANCE).andExpect(status().isForbidden());
        submit(id, StaffRole.SUPPORT).andExpect(status().isForbidden());

        save(
                        id,
                        "{\"fields\":{\"customer_email_address\":\"a@b.c\"},\"files\":{\"receipt\":\"E9\"}}",
                        StaffRole.TRUST_SAFETY)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'fields.customer_email_address')].message")
                        .value("Pick an evidence field from the list."))
                .andExpect(jsonPath("$.errors[?(@.field == 'files.receipt')].message")
                        .value("Pick a file from this case's evidence."));
        save(id, "{\"fields\":{\"uncategorized_text\":\"" + "x".repeat(5001) + "\"}}", StaffRole.TRUST_SAFETY)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Keep each answer under 5,000 characters."));
        submit(id, StaffRole.TRUST_SAFETY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("evidence_empty"));

        save(id, """
                        {"fields":{"product_description":"Brake pads and rotors replaced at the customer's home.",
                                   "service_date":"2026-09-04","uncategorized_text":" "},
                         "files":{"receipt":"E1"}}""", StaffRole.TRUST_SAFETY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.fields.service_date").value("2026-09-04"))
                .andExpect(jsonPath("$.detail.fields.uncategorized_text").doesNotExist())
                .andExpect(jsonPath("$.detail.files.receipt").value("E1"))
                .andExpect(jsonPath("$.detail.updatedBy").value(agent));

        submit(id, StaffRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.row.submittedAt").isNotEmpty())
                .andExpect(jsonPath("$.detail.submittedBy").value(agent));
        assertThat(jdbc.sql(
                                "select stripe_files ->> 'receipt' from payments.card_dispute_evidence where dispute_id = ?")
                        .params(id)
                        .query(String.class)
                        .single())
                .startsWith("file_local_");
        assertThat(jdbc.sql("select count(*) from developer.audit_log where action = 'disputes.evidence_submitted'"
                                + " and target_id = ? and actor_id = ?")
                        .params(id, agent)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        submit(id, StaffRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("evidence_submitted"));
        save(id, "{\"fields\":{\"service_date\":\"2026-09-05\"}}", StaffRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("evidence_submitted"));
    }

    @Test
    void aRefusalKeepsTheDraftWithTheError_closedAndOverdueDisputesCantTakeEvidence() throws Exception {
        var refused = chargeback("dp_rejectEvidence_" + Ids.next());
        save(refused, "{\"fields\":{\"product_description\":\"Brakes\"}}", StaffRole.TRUST_SAFETY)
                .andExpect(status().isOk());
        submit(refused, StaffRole.TRUST_SAFETY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stripe_rejected"));
        mvc.perform(get(DESK + "/{id}", refused).with(TestJwt.staff(agent, StaffRole.TRUST_SAFETY)))
                .andExpect(jsonPath("$.detail.lastError").value(org.hamcrest.Matchers.containsString("not accepting")))
                .andExpect(jsonPath("$.detail.fields.product_description").value("Brakes"))
                .andExpect(jsonPath("$.item.row.submittedAt").isEmpty());

        var overdue = chargeback("dp_" + Ids.next());
        jdbc.sql("update payments.disputes set stripe_evidence_due_by = now() - interval '1 hour' where id = ?")
                .params(overdue)
                .update();
        save(overdue, "{\"fields\":{\"product_description\":\"Brakes\"}}", StaffRole.TRUST_SAFETY)
                .andExpect(status().isOk());
        submit(overdue, StaffRole.TRUST_SAFETY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("evidence_overdue"));

        var closed = chargeback("dp_" + Ids.next());
        jdbc.sql(
                        "update payments.disputes set state = 'decided', decision = 'release', decided_at = now() where id = ?")
                .params(closed)
                .update();
        save(closed, "{\"fields\":{\"product_description\":\"Brakes\"}}", StaffRole.TRUST_SAFETY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("dispute_closed"));

        var customerCase = fx.dispute(
                shop.merchantId(),
                fx.escrow(shop.merchantId(), "service", "held", 5_000, 900, "Job", "B. Lee", hoursAgo(5), inHours(2)),
                5_000,
                PaymentsFixture.caseNumber("DS"));
        save(customerCase, "{}", StaffRole.TRUST_SAFETY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_card_dispute"));
        mvc.perform(get(DESK + "/{id}", Ids.next()).with(TestJwt.staff(agent, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isNotFound());
    }
}
