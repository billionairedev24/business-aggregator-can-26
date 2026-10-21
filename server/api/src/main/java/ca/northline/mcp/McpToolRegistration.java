package ca.northline.mcp;

import io.modelcontextprotocol.server.McpSyncServer;
import io.swagger.v3.oas.models.OpenAPI;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.ai.mcp.OpenApiMcpToolCallbackProvider;
import org.springdoc.api.AbstractOpenApiResource;
import org.springdoc.core.service.OpenAPIService;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.util.ReflectionUtils;

/**
 * S-163: registers the MCP tools (S-127) once the api is up, off the start-up path.
 *
 * <p>The tools come from the api's OpenAPI document. springdoc used to build every document while the application
 * started (pre-loading: the default one and the six groups, in parallel, ≈ 150 MiB of extra heap on a one-CPU pod) and
 * then held start-up for up to 30 s waiting for the default one; on a busy pod the wait ran out and no tool was
 * registered at all. Now {@code springdoc.pre-loading-enabled} is off, springdoc's waiting registrar is removed
 * ({@link McpConfiguration#withoutSpringdocToolWait}), and on {@link ApplicationReadyEvent} one virtual thread builds
 * the default document — the same call pre-loading made — hands it to the tool provider and adds the tools to the MCP
 * server. The groups' documents are built when a viewer first asks for them (dev, staging). {@link McpGatewayFilter}
 * lets MCP requests that arrive before that wait for it ({@link #await}).
 *
 * <p>springdoc offers no public way to do either step, so two members are reached by reflection; {@code McpServerTest}
 * fails if an upgrade renames them: {@code AbstractOpenApiResource#getOpenApi(Locale)} (protected; builds the document
 * without a request) and the provider's {@code openAPIService} field ({@code OpenAPIService} is a new instance per
 * user, so the provider's never sees a document the resource built, which is also why pre-loading's registration
 * could time out).
 */
@Slf4j
final class McpToolRegistration {

    private final ObjectProvider<OpenApiMcpToolCallbackProvider> tools;
    private final ObjectProvider<OpenApiWebMvcResource> openApi;
    private final ObjectProvider<McpSyncServer> server;
    private final CompletableFuture<Integer> registered = new CompletableFuture<>();

    McpToolRegistration(
            ObjectProvider<OpenApiMcpToolCallbackProvider> tools,
            ObjectProvider<OpenApiWebMvcResource> openApi,
            ObjectProvider<McpSyncServer> server) {
        this.tools = tools;
        this.openApi = openApi;
        this.server = server;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        Thread.ofVirtual().name("mcp-tools").start(this::register);
    }

    /** Builds the default OpenAPI document and registers its tools (none without MCP). */
    void register() {
        try {
            var provider = tools.getIfAvailable();
            var mcp = server.getIfAvailable();
            var resource = openApi.getIfAvailable();
            if (provider == null || mcp == null || resource == null) {
                registered.complete(0); // springdoc.ai.mcp.enabled=false (most tests) or no MCP server
                return;
            }
            var started = System.nanoTime();
            var locale = Locale.getDefault(); // the locale the provider reads
            openApiServiceOf(provider).setCachedOpenAPI(buildDocument(resource, locale), locale);
            var callbacks = provider.getToolCallbacks();
            for (var callback : callbacks) {
                mcp.addTool(McpToolUtils.toSyncToolSpecification(callback));
            }
            mcp.notifyToolsListChanged();
            log.info(
                    "MCP: {} tools registered (OpenAPI document built in {} ms)",
                    callbacks.length,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            registered.complete(callbacks.length);
        } catch (RuntimeException e) {
            log.error("MCP: the tools could not be registered; /mcp answers 503 mcp_unavailable", e);
            registered.completeExceptionally(e);
        }
    }

    /**
     * True once the tools are registered; waits at most {@code timeout} for the registration after start-up. False if
     * it is still running then, or failed.
     */
    boolean await(Duration timeout) {
        try {
            registered.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException | ExecutionException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** {@code AbstractOpenApiResource#getOpenApi(Locale)}: what springdoc's pre-loading ran for each document. */
    private static OpenAPI buildDocument(OpenApiWebMvcResource resource, Locale locale) {
        var method = ReflectionUtils.findMethod(AbstractOpenApiResource.class, "getOpenApi", Locale.class);
        if (method == null) {
            throw new IllegalStateException(
                    "springdoc has no AbstractOpenApiResource#getOpenApi(Locale) any more: see McpToolRegistration");
        }
        ReflectionUtils.makeAccessible(method);
        if (ReflectionUtils.invokeMethod(method, resource, locale) instanceof OpenAPI document) {
            return document;
        }
        throw new IllegalStateException("springdoc built no OpenAPI document for the MCP tools");
    }

    private static OpenAPIService openApiServiceOf(OpenApiMcpToolCallbackProvider provider) {
        var field = ReflectionUtils.findField(OpenApiMcpToolCallbackProvider.class, "openAPIService");
        if (field == null) {
            throw new IllegalStateException(
                    "springdoc's OpenApiMcpToolCallbackProvider has no openAPIService any more: see McpToolRegistration");
        }
        ReflectionUtils.makeAccessible(field);
        if (ReflectionUtils.getField(field, provider) instanceof OpenAPIService service) {
            return service;
        }
        throw new IllegalStateException("springdoc's MCP tool provider has no OpenAPIService");
    }
}
