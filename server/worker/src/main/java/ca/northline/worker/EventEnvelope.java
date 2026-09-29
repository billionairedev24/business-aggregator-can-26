package ca.northline.worker;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

public record EventEnvelope(
        String id, String type, int version, Instant occurredAt, String aggregateId, String traceId, JsonNode data) {}
