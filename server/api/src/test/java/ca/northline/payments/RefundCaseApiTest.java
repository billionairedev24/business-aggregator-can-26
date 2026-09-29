package ca.northline.payments;

import static ca.northline.payments.PaymentsFixture.hoursAgo;
import static ca.northline.payments.PaymentsFixture.inHours;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.payments.api.CustomerCases;
import ca.northline.payments.api.DisputeDecided;
import ca.northline.payments.api.DisputeDecisions;
import ca.northline.payments.api.EscrowReleased;
import ca.northline.payments.api.RefundIssued;
import ca.northline.payments.application.PaymentsJobs;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Refunds &amp; disputes: open cases, responses, evidence, offers, refunds (queued, never instant), agent decisions. */
@Import(PaymentsFixture.class)
@RecordApplicationEvents
class RefundCaseApiTest extends IntegrationTest {

    @Autowired
    PaymentsFixture fx;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PaymentsJobs jobs;

    @Autowired
    CustomerCases customers;

    @Autowired
    DisputeDecisions decisions;

    PaymentsFixture.Shop shop;
    String escrowId;
    String disputeId;

    @BeforeEach
    void seed() {
        shop = fx.shop("provider", "master");
        escrowId = fx.escrow(
                shop.merchantId(), "service", "held", 16_000, 900, "Pre-purchase", "A. Osei", hoursAgo(90), inHours(2));
        disputeId = fx.dispute(shop.merchantId(), escrowId, 16_000, PaymentsFixture.caseNumber("DS"));
    }

    private String url(String path) {
        return "/api/v1/merchants/" + shop.merchantId() + path;
    }

    private String escrowState() {
        return jdbc.sql("select state from payments.escrows where id = ?")
                .params(escrowId)
                .query(String.class)
                .single();
    }

    @Test
    void overview_openDisputeAndHistory_forEveryMember() throws Exception {
        var technician = fx.member(shop, MerchantRole.TECHNICIAN);
        mvc.perform(get(url("/refunds")).with(TestJwt.member(technician)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openDisputes").value(1))
                .andExpect(jsonPath("$.open", hasSize(1)))
                .andExpect(jsonPath("$.open[0].type").value("dispute"))
                .andExpect(jsonPath("$.open[0].subject").value("Pre-purchase inspection"))
                .andExpect(jsonPath("$.open[0].customerName").value("A. Osei"))
                .andExpect(jsonPath("$.open[0].amountCents").value(16_000))
                .andExpect(jsonPath("$.open[0].state").value("open"))
                .andExpect(jsonPath("$.history[0].outcome").value("awaiting_you"))
                .andExpect(jsonPath("$.disputeRateFloorBps").value(100));
        mvc.perform(get(url("/refunds")).with(TestJwt.member(data.user("Stranger"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(url("/refunds")).with(TestJwt.memberWithoutMfa(shop.ownerId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
    }

    @Nested
    class Responding {

        @Test
        void technicianDraftsResponseAndUploadsEvidence_butCannotOffer() throws Exception {
            var technician = fx.member(shop, MerchantRole.TECHNICIAN);
            mvc.perform(put(url("/disputes/" + disputeId + "/response"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"response\":\"Inspection photos show dry hoses at 2:14 pm on Sep 4.\"}")
                            .with(TestJwt.member(technician)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.response").value("Inspection photos show dry hoses at 2:14 pm on Sep 4."));
            var body = mvc.perform(post(url("/disputes/" + disputeId + "/evidence"))
                            .contentType(MediaType.IMAGE_PNG)
                            .header("X-File-Name", "hoses%20dry.png")
                            .content(new byte[] {(byte) 0x89, 'P', 'N', 'G'})
                            .with(TestJwt.member(technician)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.evidence[0].kind").value("photo"))
                    .andExpect(jsonPath("$.evidence[0].name").value("hoses dry.png"))
                    .andExpect(jsonPath("$.evidence[0].downloadable").value(true))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String evidenceId = JsonPath.read(body, "$.evidence[0].id");
            mvc.perform(get(url("/disputes/" + disputeId + "/evidence/" + evidenceId))
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_PNG))
                    .andExpect(content().bytes(new byte[] {(byte) 0x89, 'P', 'N', 'G'}));
            mvc.perform(post(url("/disputes/" + disputeId + "/goodwill-offer"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amountCents\":8000}")
                            .header("Idempotency-Key", Ids.next())
                            .with(TestJwt.member(technician)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
        }

        @Test
        void bookkeeperCannotRespond() throws Exception {
            mvc.perform(put(url("/disputes/" + disputeId + "/response"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"response\":\"x\"}")
                            .with(TestJwt.member(fx.member(shop, MerchantRole.BOOKKEEPER))))
                    .andExpect(status().isForbidden());
        }

        @Test
        void responseTooLong_is422() throws Exception {
            mvc.perform(put(url("/disputes/" + disputeId + "/response"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"response\":\"" + "x".repeat(2001) + "\"}")
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("response"))
                    .andExpect(jsonPath("$.errors[0].message").value("Keep your response under 2,000 characters."));
        }

        @Test
        void evidenceRules() throws Exception {
            mvc.perform(post(url("/disputes/" + disputeId + "/evidence"))
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("hello")
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("file"))
                    .andExpect(jsonPath("$.errors[0].message").value("Upload a photo (JPG, PNG, HEIC) or a PDF."));
            mvc.perform(post(url("/disputes/" + disputeId + "/evidence"))
                            .contentType(MediaType.APPLICATION_PDF)
                            .content(new byte[0])
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Choose a file to upload."));
            mvc.perform(post(url("/disputes/" + disputeId + "/evidence"))
                            .contentType(MediaType.APPLICATION_PDF)
                            .content(new byte[10 * 1024 * 1024 + 1])
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Files can be up to 10 MB."));
        }

        @Test
        void contestNeedsTheWrittenResponse_thenGoesToAnAgent() throws Exception {
            mvc.perform(post(url("/disputes/" + disputeId + "/contest")).with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("response"))
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Write your response before sending this to an agent."));
            mvc.perform(put(url("/disputes/" + disputeId + "/response"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"response\":\"Leak developed after the inspection.\"}")
                    .with(TestJwt.member(shop.ownerId())));
            mvc.perform(post(url("/disputes/" + disputeId + "/contest")).with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("agent"));
            assertThat(escrowState()).isEqualTo("disputed");
        }
    }

    @Nested
    class Money {

        @Test
        void goodwillOffer_customerAccepts_partialRefundQueued_restReleased() throws Exception {
            mvc.perform(post(url("/disputes/" + disputeId + "/goodwill-offer"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amountCents\":8000}")
                            .header("Idempotency-Key", Ids.next())
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("seller_replied"))
                    .andExpect(jsonPath("$.offer.amountCents").value(8_000))
                    .andExpect(jsonPath("$.offer.state").value("pending"));

            decisions.acceptOffer(disputeId, "cust-A. Osei");
            assertThat(events.stream(DisputeDecided.class))
                    .anyMatch(e ->
                            e.aggregateId().equals(disputeId) && e.decision().equals("goodwill"));
            assertThat(events.stream(EscrowReleased.class))
                    .anyMatch(e -> e.aggregateId().equals(escrowId));
            assertThat(escrowState()).isEqualTo("released");
            // refunds are never instant: queued until the refund job pays it
            var balanceBefore = fx.balance(shop.merchantId());
            assertThat(balanceBefore).isEqualTo(16_000 - 1_440);
            jobs.payRefundQueue();
            assertThat(events.stream(RefundIssued.class))
                    .anyMatch(e -> e.escrowId() != null && e.escrowId().equals(escrowId));
            assertThat(fx.balance(shop.merchantId())).isEqualTo(balanceBefore - 8_000);
        }

        @Test
        void offerMustBeLessThanTheFullAmount() throws Exception {
            mvc.perform(post(url("/disputes/" + disputeId + "/goodwill-offer"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amountCents\":16000}")
                            .header("Idempotency-Key", Ids.next())
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("amountCents"))
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Offer less than the full amount — or choose Full refund."));
            mvc.perform(post(url("/disputes/" + disputeId + "/goodwill-offer"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header("Idempotency-Key", Ids.next())
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Offer less than the full amount — or choose Full refund."));
        }

        @Test
        void fullRefund_closesTheCase_escrowRefunded_notTheMerchantsBalance() throws Exception {
            mvc.perform(post(url("/disputes/" + disputeId + "/full-refund"))
                            .header("Idempotency-Key", Ids.next())
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("decided"));
            assertThat(escrowState()).isEqualTo("refunded");
            jobs.payRefundQueue();
            assertThat(fx.balance(shop.merchantId())).isZero();
            mvc.perform(get(url("/refunds")).with(TestJwt.member(shop.ownerId())))
                    .andExpect(jsonPath("$.openDisputes").value(0))
                    .andExpect(jsonPath("$.history[0].outcome").value("lost"));
        }

        @Test
        void agentDecidesRelease_escrowReleased() {
            decisions.decide(disputeId, DisputeDecisions.Decision.RELEASE, 0, data.user("Agent"));
            assertThat(escrowState()).isEqualTo("released");
            assertThat(events.stream(DisputeDecided.class))
                    .anyMatch(e -> e.decision().equals("release"));
            assertThat(fx.balance(shop.merchantId())).isEqualTo(16_000 - 1_440);
        }
    }

    @Nested
    class RefundCases {

        String releasedEscrow;

        @BeforeEach
        void released() {
            releasedEscrow = fx.escrow(
                    shop.merchantId(),
                    "goods",
                    "released",
                    3_800,
                    900,
                    "Wiper blades ×2",
                    "P. Nguyen",
                    hoursAgo(300),
                    hoursAgo(100));
        }

        @Test
        void customerRequest_heldBackFromPayouts_acceptedAndQueued_thenPaid() throws Exception {
            var refundId = customers.requestRefund(releasedEscrow, "cust-P. Nguyen", 3_800, "Wiper blades wrong size");
            mvc.perform(get("/api/v1/merchants/{id}/payouts/overview", shop.merchantId())
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(jsonPath("$.availableCents").value(0));
            mvc.perform(get(url("/refunds")).with(TestJwt.member(shop.ownerId())))
                    .andExpect(jsonPath("$.open[?(@.type == 'refund')].auto").value(false));
            mvc.perform(post(url("/refunds/" + refundId + "/accept"))
                            .header("Idempotency-Key", Ids.next())
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("approved"));
            jobs.payRefundQueue();
            mvc.perform(get(url("/refunds")).with(TestJwt.member(shop.ownerId())))
                    .andExpect(jsonPath("$.history[?(@.id == '" + refundId + "')].outcome")
                            .value("refunded"));
        }

        @Test
        void smallRefund_notContestedIn48h_isApprovedAutomatically() {
            var refundId = customers.requestRefund(releasedEscrow, "cust-P. Nguyen", 1_900, "One blade wrong size");
            jdbc.sql("update payments.refunds set contest_by = now() - interval '1 minute' where id = ?")
                    .params(refundId)
                    .update();
            jobs.lapseCases();
            assertThat(jdbc.sql("select state from payments.refunds where id = ?")
                            .params(refundId)
                            .query(String.class)
                            .single())
                    .isEqualTo("approved");
        }

        @Test
        void contest_needsAReason_thenGoesToAnAgent() throws Exception {
            var refundId = fx.refundCase(shop.merchantId(), releasedEscrow, 3_800, PaymentsFixture.caseNumber("RF"));
            mvc.perform(post(url("/refunds/" + refundId + "/contest"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\" \"}")
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("reason"))
                    .andExpect(
                            jsonPath("$.errors[0].message").value("Tell the agent why you're contesting this refund."));
            mvc.perform(post(url("/refunds/" + refundId + "/contest"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Customer fitted them wrong.\"}")
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("agent_review"));
            decisions.decideRefund(refundId, false, data.user("Agent"));
            assertThat(jdbc.sql("select state from payments.refunds where id = ?")
                            .params(refundId)
                            .query(String.class)
                            .single())
                    .isEqualTo("denied");
        }
    }
}
