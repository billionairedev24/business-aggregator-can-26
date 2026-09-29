package ca.northline.worker;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record EventEnvelope(String id, String type, int version, Instant occurredAt, String aggregateId, String traceId, JsonNode data) {}
