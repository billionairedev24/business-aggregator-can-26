package ca.northline.worker.webhooks;

import ca.northline.worker.events.EventEnvelope;
import ca.northline.worker.events.EventSchemas;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Domain events → public webhook payloads (S-33). The public payload is a contract of its own, versioned and
 * documented in {@code docs/spec/webhooks/<type>.v<version>.schema.json} (docs/runbooks/webhooks.md), so internal
 * events can change without breaking partners: a new internal version needs a line here first (until then it is not
 * delivered, and the log says so).
 *
 * <pre>
 * { "id": event id (ULID, dedupe key), "type": "booking.completed", "version": 1, "createdAt": ISO-8601 UTC,
 *   "merchantId": "…", "data": { … } }
 * </pre>
 *
 * <p><b>Personal data:</b> {@code data} holds only what the business already has in its Studio — its own ids (booking,
 * escrow, refund, case number, its team members' user ids) and amounts. Never customer ids, names, contact details or
 * addresses: a customer id is a cross-business identifier the partner has no use for.
 */
@Slf4j
public final class WebhookPayloads {

    public static final int VERSION = 1;
    public static final String TEST = "webhook.test";

    /** Public type ← internal {@code <type>:<version>} (what is delivered today). */
    public static final Map<String, String> SOURCES = Map.of(
            "booking.completed", "booking.booking_completed:1",
            "payment.released", "payments.escrow_released:1",
            "refund.issued", "payments.refund_issued:1",
            "order.placed", "orders.order_placed:1");

    /** Types endpoints can subscribe to (Settings › API) whose domain event isn't published on Kafka yet. */
    public static final List<String> NOT_YET_PUBLISHED =
            List.of("booking.confirmed", "order.delivered", "review.created");

    /** A payload ready to send. */
    public record PublicEvent(String eventId, String type, String merchantId, ObjectNode payload) {}

    private final JsonMapper json;
    private final EventSchemas schemas;

    public WebhookPayloads(JsonMapper json, EventSchemas schemas) {
        this.json = json;
        this.schemas = schemas;
    }

    /** The public payload of a domain event, or empty when partners don't get this event. */
    public Optional<PublicEvent> of(EventEnvelope event) {
        var d = event.data();
        var merchant = event.text("merchantId");
        var mapped = switch (event.type() + ":" + event.version()) {
            case "booking.booking_completed:1" -> {
                var data = json.createObjectNode();
                data.put("bookingId", event.aggregateId());
                data.put("completedBy", event.text("actorId"));
                data.put("photoCount", d.path("photoCount").asInt());
                yield envelope(event, "booking.completed", merchant, data);
            }
            case "payments.escrow_released:1" -> {
                var data = json.createObjectNode();
                data.put("escrowId", event.aggregateId());
                var reference = data.putObject("reference");
                reference.put("type", event.text("refType"));
                reference.put("id", event.text("refId"));
                data.put("grossCents", d.path("grossCents").asLong());
                data.put("feeCents", d.path("feeCents").asLong());
                data.put("netCents", d.path("netCents").asLong());
                data.put("currency", "CAD");
                yield envelope(event, "payment.released", merchant, data);
            }
            case "payments.refund_issued:1" -> {
                var data = json.createObjectNode();
                data.put("refundId", event.aggregateId());
                data.put("caseNumber", event.optionalText("caseNumber"));
                data.put("escrowId", event.optionalText("escrowId"));
                data.put("amountCents", d.path("amountCents").asLong());
                data.put("currency", "CAD");
                data.put("chargedTo", event.text("chargedTo"));
                yield envelope(event, "refund.issued", merchant, data);
            }
            case "orders.order_placed:1" -> {
                // S-51: one event per shop, that shop's lines only; the customer id stays out (see class comment)
                var data = json.createObjectNode();
                data.put("orderId", event.aggregateId());
                data.put("orderRef", event.text("orderRef"));
                data.put("orderType", event.text("orderType"));
                data.put("delivery", event.text("delivery"));
                data.put("windowId", event.optionalText("windowId"));
                var lines = data.putArray("lines");
                for (var line : d.path("lines")) {
                    var out = lines.addObject();
                    out.put("lineId", line.path("lineId").asString());
                    out.put("offerId", line.path("offerId").asString());
                    out.put(
                            "variantId",
                            line.path("variantId").isNull()
                                            || line.path("variantId").isMissingNode()
                                    ? null
                                    : line.path("variantId").asString());
                    out.put("qty", line.path("qty").asInt());
                    out.put("amountCents", line.path("amountCents").asLong());
                }
                data.put("subtotalCents", d.path("subtotalCents").asLong());
                data.put("taxCents", d.path("taxCents").asLong());
                data.put("currency", "CAD");
                yield envelope(event, "order.placed", merchant, data);
            }
            default -> null;
        };
        if (mapped == null && SOURCES.containsKey(publicTypeOf(event.type()))) {
            log.warn("{} v{} has no public webhook mapping yet — not delivered", event.type(), event.version());
        }
        return Optional.ofNullable(mapped).map(this::checked);
    }

    /** The {@code webhook.test} payload of "Send test event". */
    public PublicEvent test(String eventId, String merchantId, String endpointId, Instant at) {
        var data = json.createObjectNode();
        data.put("endpointId", endpointId);
        return checked(envelope(eventId, TEST, merchantId, at, data));
    }

    private PublicEvent envelope(EventEnvelope event, String type, String merchantId, ObjectNode data) {
        return envelope(event.id(), type, merchantId, event.occurredAt(), data);
    }

    private PublicEvent envelope(String eventId, String type, String merchantId, Instant at, ObjectNode data) {
        var payload = json.createObjectNode();
        payload.put("id", eventId);
        payload.put("type", type);
        payload.put("version", VERSION);
        payload.put("createdAt", at.toString());
        payload.put("merchantId", merchantId);
        payload.set("data", data);
        return new PublicEvent(eventId, type, merchantId, payload);
    }

    /** Every payload leaves only if it matches its published schema (a mapping bug is retried, then dead-lettered). */
    private PublicEvent checked(PublicEvent event) {
        var problems = validate(event.type(), event.payload());
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "webhook " + event.type() + " v" + VERSION + " breaks its public schema: " + problems);
        }
        return event;
    }

    List<String> validate(String type, JsonNode payload) {
        return schemas.validate("webhook." + type, VERSION, payload)
                .orElseThrow(() -> new IllegalStateException("no public schema for webhook " + type + " v" + VERSION));
    }

    private static String publicTypeOf(String internalType) {
        return SOURCES.entrySet().stream()
                .filter(e -> e.getValue().startsWith(internalType + ":"))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("");
    }
}
