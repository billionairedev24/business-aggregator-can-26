package ca.northline.mcp;

import ca.northline.developer.api.AuditTrail;
import io.modelcontextprotocol.server.McpSyncServer;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.ai.customizers.McpToolCustomizer;
import org.springdoc.ai.mcp.OpenApiMcpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** S-127 wiring: which operations become tools, the filters around {@code /mcp}, the call store. */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
class McpConfiguration {

    /** Filters registered after Spring Security's chain (order -100) see the validated token. */
    static final int AFTER_SECURITY = 0;

    /**
     * Only the operations of {@link AgentTools} become MCP tools, under their names and descriptions; reads are safe.
     * Writes are confirmed by {@link McpGatewayFilter} (springdoc's own approval guardrail is off: one confirmation).
     */
    @Bean
    McpToolCustomizer northlineMcpTools() {
        return (context, path, method, _) -> {
            var tool = AgentTools.forOperation(method.name(), path);
            if (tool.isEmpty()) {
                context.setExclude(true);
                return context;
            }
            context.setName(tool.get().name());
            context.setDescription(tool.get().description());
            context.setSafeEndpoint(!tool.get().kind().writes());
            return context;
        };
    }

    @Bean
    AgentCalls agentCalls(
            McpProperties props, ObjectProvider<StringRedisTemplate> redis, Clock clock, Environment env) {
        if (props.store() == McpProperties.Store.MEMORY) {
            if (env.matchesProfiles("staging | prod")) {
                throw new IllegalStateException("MCP_STORE=memory is not allowed under staging/prod: confirmations and"
                        + " rate limits must be shared by every api replica (MCP_STORE=redis, docs/runbooks/mcp.md)");
            }
            return new MemoryAgentCalls(clock);
        }
        return new RedisAgentCalls(redis.getObject(), clock);
    }

    @Bean
    McpAgentHeaderFilter mcpAgentHeaderFilter() {
        return new McpAgentHeaderFilter();
    }

    /** Before anything reads the MCP request's headers (springdoc captures them for the tool calls). */
    @Bean
    FilterRegistrationBean<McpAgentHeaderFilter> mcpAgentHeaderRegistration(McpAgentHeaderFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(McpGatewayFilter.ENDPOINT, McpGatewayFilter.ENDPOINT + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    FilterRegistrationBean<McpGatewayFilter> mcpGatewayRegistration(
            ObjectProvider<McpSyncServer> server,
            ObjectProvider<OpenApiMcpToolCallbackProvider> tools,
            McpProperties props,
            AgentCalls calls,
            AuditTrail audit,
            JsonMapper json,
            Clock clock,
            McpAgentHeaderFilter agentHeader) {
        var registration = new FilterRegistrationBean<>(new McpGatewayFilter(
                server::getObject, tools::getObject, props, calls, audit, json, clock, agentHeader));
        registration.addUrlPatterns(McpGatewayFilter.ENDPOINT);
        registration.setOrder(AFTER_SECURITY);
        log.info("MCP server: {} (Streamable HTTP), store={}", props.resource(), props.store());
        return registration;
    }

    @Bean
    FilterRegistrationBean<McpTokenConfinement> mcpTokenConfinement(
            McpProperties props, McpAgentHeaderFilter agentHeader) {
        var registration = new FilterRegistrationBean<>(new McpTokenConfinement(props, agentHeader));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(AFTER_SECURITY);
        return registration;
    }
}
