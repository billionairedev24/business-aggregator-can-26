/**
 * The built-in MCP server (S-127, docs/runbooks/mcp.md): AI agents — Claude and other Model Context Protocol clients —
 * act for a merchant, a partner or Northline staff through the same REST operations the Studio uses. springdoc turns a
 * short catalogue of documented operations ({@link ca.northline.mcp.AgentTools}) into MCP tools; Spring AI serves them
 * over Streamable HTTP at {@code /mcp}; {@link ca.northline.mcp.McpGatewayFilter} is the policy in front (audience,
 * second factor, scopes, rate limits, confirmation of writes, repeat protection, audit, resources). Each tool call is
 * an HTTP request back to this api with the caller's own token, so merchant binding, roles, {@code acr=mfa}, partner
 * scopes and validation apply exactly as for the Studio. No schema of its own.
 */
@ApplicationModule(displayName = "mcp")
@NullMarked
package ca.northline.mcp;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
