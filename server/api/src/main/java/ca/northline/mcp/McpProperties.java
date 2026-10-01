package ca.northline.mcp;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.mcp.*} (S-127, docs/runbooks/mcp.md).
 *
 * @param resource the MCP server's canonical URI (RFC 8707 resource indicator): tokens must name it in {@code aud}
 *     — {@code ${API_PUBLIC_URL}/mcp}
 * @param authorizationServer the issuer agents get tokens from (RFC 9728 metadata) — {@code ${AUTH_ISSUER}}
 * @param callsPerMinute tool calls per caller per minute
 * @param writesPerMinute write tool calls among them
 * @param confirmWindow how long a write waits for its confirming second call
 * @param repeatWindow an identical write repeated within this is answered "already done", not applied twice
 * @param store where rate counters and pending confirmations live: {@code redis} (shared by every replica; the
 *     deployed default) or {@code memory} (one instance: local, test)
 */
@ConfigurationProperties("northline.mcp")
public record McpProperties(
        String resource,
        String authorizationServer,
        @DefaultValue("60") int callsPerMinute,
        @DefaultValue("10") int writesPerMinute,
        @DefaultValue("5m") Duration confirmWindow,
        @DefaultValue("10m") Duration repeatWindow,
        @DefaultValue("memory") Store store) {

    public enum Store {
        MEMORY,
        REDIS
    }
}
