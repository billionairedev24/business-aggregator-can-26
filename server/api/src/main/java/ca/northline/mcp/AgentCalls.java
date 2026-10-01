package ca.northline.mcp;

import java.time.Duration;
import java.util.Optional;

/**
 * Outbound port: the short-lived state of agents' calls (S-127) — per-minute counters and the marks of pending and
 * applied writes. {@code redis} in every deployed environment (all replicas see the same state), {@code memory} on one
 * instance ({@code northline.mcp.store}).
 */
public interface AgentCalls {

    /** Counts one call against {@code key}'s current minute; false when the limit is already reached. */
    boolean allow(String key, int perMinute);

    /** Stores {@code value} under {@code key} for {@code ttl} unless the key exists; true when it was stored. */
    boolean putIfAbsent(String key, String value, Duration ttl);

    Optional<String> get(String key);

    /** Removes the key; true when it existed (a confirmation consumes its pending mark once). */
    boolean remove(String key);
}
