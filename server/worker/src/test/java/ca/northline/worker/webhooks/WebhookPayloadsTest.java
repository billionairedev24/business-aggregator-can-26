package ca.northline.worker.webhooks;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.worker.events.EventEnvelope;
import ca.northline.worker.events.EventSchemas;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Internal events → the documented public payloads (docs/spec/webhooks), ids and amounts only. */
class WebhookPayloadsTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String MERCHANT = "01J9ZD3V00000000000000PWM1";
    static final Instant AT = Instant.parse("2026-09-30T18:00:00Z");

    final WebhookPayloads payloads =
            new WebhookPayloads(JSON, EventSchemas.fromClasspath(JSON, "classpath*:webhooks/*.schema.json"));

    @Test
    void bookingCompleted() {
        var out = payloads.of(event("booking.booking_completed", 1, """
                        {"eventId":"01J9ZD3V000000000000000EV1","occurredAt":"2026-09-30T18:00:00Z",
                         "aggregateId":"bk_1","merchantId":"%s","actorId":"u_tech","photoCount":3}""".formatted(MERCHANT)))
                .getFirst();

        assertThat(out.type()).isEqualTo("booking.completed");
        assertThat(out.payload().toString())
                .isEqualTo("{\"id\":\"01J9ZD3V000000000000000EV1\",\"type\":\"booking.completed\",\"version\":1,"
                        + "\"createdAt\":\"2026-09-30T18:00:00Z\",\"merchantId\":\"" + MERCHANT + "\",\"data\":"
                        + "{\"bookingId\":\"bk_1\",\"completedBy\":\"u_tech\",\"photoCount\":3}}");
    }

    @Test
    void bookingConfirmed_withoutTheCustomer() {
        var out = payloads.of(event("booking.booking_confirmed", 1, """
                        {"eventId":"01J9ZD3V000000000000000EV4","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"bk_2",
                         "merchantId":"%s","customerId":"u_customer","memberUserId":"u_tech","serviceId":"svc_1",
                         "quoteId":null,"bookingType":"visit","startsAt":"2026-10-02T15:00:00Z",
                         "endsAt":"2026-10-02T16:00:00Z","priceCents":8900,"depositCents":8900}""".formatted(MERCHANT)))
                .getFirst();

        assertThat(out.type()).isEqualTo("booking.confirmed");
        assertThat(out.payload().path("data").toString())
                .isEqualTo("{\"bookingId\":\"bk_2\",\"memberUserId\":\"u_tech\",\"serviceId\":\"svc_1\","
                        + "\"quoteId\":null,\"bookingType\":\"visit\",\"startsAt\":\"2026-10-02T15:00:00Z\","
                        + "\"endsAt\":\"2026-10-02T16:00:00Z\",\"priceCents\":8900,\"depositCents\":8900,"
                        + "\"currency\":\"CAD\"}");
        assertThat(out.payload().toString()).doesNotContain("u_customer");
    }

    @Test
    void escrowReleasedIsPaymentReleased() {
        var out = payloads.of(event("payments.escrow_released", 1, """
                        {"eventId":"01J9ZD3V000000000000000EV2","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"esc_1",
                         "merchantId":"%s","refType":"booking","refId":"bk_1","grossCents":38900,"feeCents":3890,
                         "netCents":35010}""".formatted(MERCHANT)))
                .getFirst();

        assertThat(out.type()).isEqualTo("payment.released");
        assertThat(out.payload().path("data").toString())
                .isEqualTo("{\"escrowId\":\"esc_1\",\"reference\":{\"type\":\"booking\",\"id\":\"bk_1\"},"
                        + "\"grossCents\":38900,\"feeCents\":3890,\"netCents\":35010,\"currency\":\"CAD\"}");
    }

    @Test
    void refundIssued_withoutTheOptionalCaseNumber() {
        var out = payloads.of(event("payments.refund_issued", 1, """
                        {"eventId":"01J9ZD3V000000000000000EV3","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"rf_1",
                         "merchantId":"%s","escrowId":null,"amountCents":4500,"chargedTo":"merchant"}""".formatted(MERCHANT)))
                .getFirst();

        assertThat(out.payload().path("data").toString())
                .isEqualTo("{\"refundId\":\"rf_1\",\"caseNumber\":null,\"escrowId\":null,\"amountCents\":4500,"
                        + "\"currency\":\"CAD\",\"chargedTo\":\"merchant\"}");
    }

    @Test
    void orderPlaced_theShopsLinesWithoutTheCustomer() {
        var out = payloads.of(event("orders.order_placed", 1, """
                        {"eventId":"01J9ZD3V000000000000000EV9","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"ord_1",
                         "merchantId":"%s","customerId":"u_customer","orderRef":"NL-50001","orderType":"goods",
                         "delivery":"pooled","windowId":"win_1","subtotalCents":1500,"taxCents":75,
                         "lines":[{"lineId":"ln_1","offerId":"of_1","variantId":null,"qty":2,"amountCents":1500}]}""".formatted(MERCHANT)))
                .getFirst();

        assertThat(out.type()).isEqualTo("order.placed");
        assertThat(out.merchantId()).isEqualTo(MERCHANT);
        assertThat(out.payload().path("data").toString())
                .isEqualTo("{\"orderId\":\"ord_1\",\"orderRef\":\"NL-50001\",\"orderType\":\"goods\","
                        + "\"delivery\":\"pooled\",\"windowId\":\"win_1\",\"lines\":[{\"lineId\":\"ln_1\","
                        + "\"offerId\":\"of_1\",\"variantId\":null,\"qty\":2,\"amountCents\":1500}],"
                        + "\"subtotalCents\":1500,\"taxCents\":75,\"currency\":\"CAD\"}");
        assertThat(out.payload().toString()).doesNotContain("u_customer");
    }

    @Test
    void orderDelivered_oneCopyPerBusinessOnTheOrder() {
        var other = "01J9ZD3V00000000000000PWP1";
        var out = payloads.of(event("orders.order_delivered", 1, """
                {"eventId":"01J9ZD3V00000000000000EV10","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"ord_1",
                 "orderType":"goods","proof":"photo","merchantIds":["%s","%s"]}""".formatted(MERCHANT, other)));

        assertThat(out).extracting(WebhookPayloads.PublicEvent::merchantId).containsExactly(MERCHANT, other);
        assertThat(out).allSatisfy(e -> {
            assertThat(e.type()).isEqualTo("order.delivered");
            assertThat(e.eventId()).isEqualTo("01J9ZD3V00000000000000EV10");
            assertThat(e.payload().path("merchantId").asString()).isEqualTo(e.merchantId());
            assertThat(e.payload().path("data").toString())
                    .isEqualTo("{\"orderId\":\"ord_1\",\"orderType\":\"goods\",\"proof\":\"photo\"}");
        });
    }

    @Test
    void orderEventsWithoutABusinessAreSkipped_notFailed() {
        // published before merchantIds was added (S-78 v1): nobody to deliver to — not a reason for the DLQ
        var olderDelivered = payloads.of(event("orders.order_delivered", 1, """
                {"eventId":"01J9ZD3V00000000000000EV11","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"ord_1",
                 "orderType":"food","proof":"pin"}"""));
        // order.confirmed carries no business and has no public type
        var confirmed = payloads.of(event("orders.order_confirmed", 1, """
                {"eventId":"01J9ZD3V00000000000000EV12","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"ord_1",
                 "orderType":"goods"}"""));

        assertThat(olderDelivered).isEmpty();
        assertThat(confirmed).isEmpty();
    }

    @Test
    void customerIdsNeverLeave() {
        var quote = payloads.of(event("booking.quote_accepted", 2, """
                {"eventId":"01J9ZD3V000000000000000EV4","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"q_1",
                 "merchantId":"%s","customerId":"u_customer","totalCents":1,"depositCents":0}""".formatted(MERCHANT)));
        var enRoute = payloads.of(event("booking.booking_en_route", 1, """
                {"eventId":"01J9ZD3V000000000000000EV5","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"bk_1",
                 "merchantId":"%s","actorId":"u_tech"}""".formatted(MERCHANT)));

        assertThat(quote).isEmpty();
        assertThat(enRoute).isEmpty();
    }

    @Test
    void aNewInternalVersionIsNotDeliveredUntilMapped() {
        assertThat(payloads.of(event("payments.refund_issued", 2, """
                        {"eventId":"01J9ZD3V000000000000000EV6","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"rf_1",
                         "merchantId":"%s","amountCents":4500,"chargedTo":"merchant"}""".formatted(MERCHANT))))
                .isEmpty();
    }

    @Test
    void testEvent() {
        var out = payloads.test("01J9ZD3V000000000000000EV7", MERCHANT, "wh_1", AT);

        assertThat(out.payload().toString())
                .isEqualTo("{\"id\":\"01J9ZD3V000000000000000EV7\",\"type\":\"webhook.test\",\"version\":1,"
                        + "\"createdAt\":\"2026-09-30T18:00:00Z\",\"merchantId\":\"" + MERCHANT + "\",\"data\":"
                        + "{\"endpointId\":\"wh_1\"}}");
    }

    @Test
    void theSchemasRefuseExtraFields() {
        var payload = payloads.test("01J9ZD3V000000000000000EV8", MERCHANT, "wh_1", AT)
                .payload();
        ((tools.jackson.databind.node.ObjectNode) payload.get("data")).put("customerEmail", "someone@example.com");

        assertThat(payloads.validate("webhook.test", payload)).containsExactly("$.data.customerEmail is not allowed");
    }

    @Test
    void everySubscribableTypeIsDeliveredOrKnownToBeWaitingForItsEvent() {
        // Settings › API offers these (developer.domain.DeveloperRules.EVENTS in the api).
        var offered = java.util.List.of(
                "booking.confirmed",
                "booking.completed",
                "order.placed",
                "order.delivered",
                "payment.released",
                "refund.issued",
                "review.created");
        assertThat(offered)
                .containsExactlyInAnyOrderElementsOf(java.util.stream.Stream.concat(
                                WebhookPayloads.SOURCES.keySet().stream(), WebhookPayloads.NOT_YET_PUBLISHED.stream())
                        .toList());
    }

    static EventEnvelope event(String type, int version, String json) {
        var data = JSON.readTree(json);
        return new EventEnvelope(
                data.path("eventId").asString(),
                type,
                version,
                Instant.parse(data.path("occurredAt").asString()),
                data.path("aggregateId").asString(),
                null,
                "topic",
                data);
    }
}
