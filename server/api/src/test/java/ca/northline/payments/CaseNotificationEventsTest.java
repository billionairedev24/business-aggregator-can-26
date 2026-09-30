package ca.northline.payments;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.payments.api.CustomerCases;
import ca.northline.payments.api.DisputeDecided;
import ca.northline.payments.api.DisputeDecisions;
import ca.northline.payments.api.DisputeUpdated;
import ca.northline.payments.api.RefundCaseUpdated;
import ca.northline.payments.api.RefundIssued;
import ca.northline.payments.application.PaymentsJobs;
import ca.northline.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * S-13: the case changes the merchant is emailed about are published as events — {@code dispute.updated},
 * {@code refund.case_updated}, and the case number on {@code dispute.decided} / {@code refund.issued}.
 */
@Import(PaymentsFixture.class)
@RecordApplicationEvents
class CaseNotificationEventsTest extends IntegrationTest {

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

    @BeforeEach
    void seed() {
        shop = fx.shop("provider", "master");
        var now = Instant.now();
        escrowId = fx.escrow(
                shop.merchantId(),
                "goods",
                "released",
                3_800,
                900,
                "Wiper blades ×2",
                "P. Nguyen",
                now.minus(300, ChronoUnit.HOURS),
                now.minus(100, ChronoUnit.HOURS));
    }

    @Test
    void aRefundRequest_isPublishedWithItsDeadline_andEachLaterChange() {
        var refundId = customers.requestRefund(escrowId, "cust-P. Nguyen", 3_800, "Wiper blades wrong size");
        jdbc.sql("update payments.refunds set contest_by = now() - interval '1 minute' where id = ?")
                .params(refundId)
                .update();
        jobs.lapseCases();
        decisions.decideRefund(refundId, true, data.user("Agent"));
        jobs.payRefundQueue();

        var updates = events.stream(RefundCaseUpdated.class)
                .filter(e -> e.aggregateId().equals(refundId))
                .toList();
        assertThat(updates)
                .extracting(RefundCaseUpdated::change)
                .containsExactly("requested", "agent_review", "approved");
        assertThat(updates.getFirst().respondBy()).isNotNull();
        assertThat(updates.getFirst().caseNumber()).startsWith("RF-");
        assertThat(updates.getFirst().merchantId()).isEqualTo(shop.merchantId());
        assertThat(updates.get(1).respondBy()).isNull();
        assertThat(events.stream(RefundIssued.class).filter(e -> e.aggregateId().equals(refundId)))
                .singleElement()
                .satisfies(e ->
                        assertThat(e.caseNumber()).isEqualTo(updates.getFirst().caseNumber()));
    }

    @Test
    void aDispute_isPublishedWhenOpened_andDecidedWithItsCaseNumberAndAmount() {
        var disputeId = customers.openDispute(escrowId, "cust-P. Nguyen", "Wrong size", "They don't fit.");
        decisions.decide(disputeId, DisputeDecisions.Decision.PARTIAL, 1_900, data.user("Agent"));

        assertThat(events.stream(DisputeUpdated.class)
                        .filter(e -> e.aggregateId().equals(disputeId)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.change()).isEqualTo("opened");
                    assertThat(e.amountCents()).isEqualTo(3_800);
                    assertThat(e.respondBy()).isAfter(Instant.now());
                    assertThat(e.caseNumber()).startsWith("DS-");
                });
        assertThat(events.stream(DisputeDecided.class)
                        .filter(e -> e.aggregateId().equals(disputeId)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.caseNumber()).startsWith("DS-");
                    assertThat(e.amountCents()).isEqualTo(3_800);
                    assertThat(e.refundCents()).isEqualTo(1_900);
                });
    }
}
