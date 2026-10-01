package ca.northline.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/**
 * The developer docs MCP server (S-128, docs/runbooks/mcp.md § Developer docs): read-only tools and resources over
 * the repository's documentation and OpenAPI documents ({@link DevDocs}), at {@value #ENDPOINT} next to the business
 * tools of S-127. A separate MCP server (its own endpoint and resource URI, stateless Streamable HTTP from the MCP
 * Java SDK) rather than more tools on {@code /mcp}: a coding agent gets the docs without a merchant sign-in, and a
 * merchant's agent doesn't see internal runbooks. Open under {@code local}; Northline staff only in the cloud
 * ({@link DevDocsAccessFilter}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "northline.devdocs", name = "enabled", havingValue = "true", matchIfMissing = true)
class DevDocsServer {

    static final String ENDPOINT = "/mcp/docs";
    static final String DOC_SCHEME = "northline-docs://docs/";
    static final String SPEC_SCHEME = "northline-docs://openapi/";

    private static final String INSTRUCTIONS = """
            Northline's developer documentation: runbooks (how to run, configure and operate every environment and \
            integration), architecture, backend conventions, decisions, and the OpenAPI documents of the api, \
            northline-auth and the BFFs. Use search_docs first, then get_document for the full text; list_operations \
            and get_operation give an endpoint's request and response schemas. Read-only.""";

    @Bean
    DevDocs devDocs(JsonMapper json, DevDocsProperties props, Environment env) {
        if (props.access() == DevDocsProperties.Access.OPEN && env.matchesProfiles("staging | prod")) {
            throw new IllegalStateException("MCP_DOCS_ACCESS=open is not allowed under staging/prod: the developer"
                    + " docs are internal (MCP_DOCS_ACCESS=staff, docs/runbooks/mcp.md)");
        }
        return DevDocs.fromClasspath(json);
    }

    @Bean(destroyMethod = "")
    HttpServletStatelessServerTransport devDocsTransport(JsonMapper json) {
        return HttpServletStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(json))
                .messageEndpoint(ENDPOINT)
                .build();
    }

    @Bean(destroyMethod = "close")
    McpStatelessSyncServer devDocsMcpServer(
            HttpServletStatelessServerTransport transport, DevDocs docs, DevDocsProperties props, JsonMapper json) {
        var tools = new DevDocsTools(docs, props, json);
        return McpServer.sync(transport)
                .serverInfo("northline-docs", "1.0.0")
                .instructions(INSTRUCTIONS)
                .jsonMapper(new JacksonMcpJsonMapper(json))
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .resources(false, false)
                        .build())
                .tools(tools.specifications())
                .resources(tools.resources())
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> devDocsServlet(
            HttpServletStatelessServerTransport transport) {
        var registration = new ServletRegistrationBean<>(transport, ENDPOINT);
        registration.setName("northlineDevDocsMcp");
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean
    FilterRegistrationBean<DevDocsAccessFilter> devDocsAccess(DevDocsProperties props) {
        var registration = new FilterRegistrationBean<>(new DevDocsAccessFilter(props));
        registration.addUrlPatterns(ENDPOINT);
        registration.setOrder(McpConfiguration.AFTER_SECURITY);
        return registration;
    }

    /** The tools and resources, over {@link DevDocs}. */
    static final class DevDocsTools {

        private final DevDocs docs;
        private final DevDocsProperties props;
        private final JsonMapper json;

        DevDocsTools(DevDocs docs, DevDocsProperties props, JsonMapper json) {
            this.docs = docs;
            this.props = props;
            this.json = json;
        }

        List<SyncToolSpecification> specifications() {
            return List.of(
                    tool(
                            "search_docs",
                            "Search Northline's developer documentation (runbooks, architecture, conventions,"
                                    + " decisions). Returns the best matching sections with their document path and"
                                    + " anchor; every word must occur.",
                            schema(
                                    Map.of(
                                            "query", stringProp("Words to look for, e.g. 'stripe webhook secret'"),
                                            "limit", intProp("At most this many sections (default 8)")),
                                    List.of("query")),
                            args -> {
                                var limit = intArg(args, "limit", props.searchResults());
                                return Map.of(
                                        "hits",
                                        docs.search(
                                                stringArg(args, "query", ""),
                                                Math.min(limit, 4 * props.searchResults())));
                            }),
                    tool(
                            "list_documents",
                            "Every document (path and title) and every OpenAPI document (name) available.",
                            schema(Map.of(), List.of()),
                            _ -> Map.of(
                                    "documents",
                                    docs.documents().stream()
                                            .map(d -> Map.of("path", d.path(), "title", d.title()))
                                            .toList(),
                                    "openapi",
                                    docs.specs().stream().map(DevDocs.Spec::name).toList())),
                    tool(
                            "get_document",
                            "A document's Markdown, by path (e.g. 'runbooks/mcp.md'). With 'section' (a heading or"
                                    + " its anchor) only that section; long documents come in pages: pass the"
                                    + " returned nextOffset as 'offset'.",
                            schema(
                                    Map.of(
                                            "path", stringProp("Path relative to docs/, from search_docs or list_documents"),
                                            "section", stringProp("Optional heading or anchor"),
                                            "offset", intProp("Optional character offset for the next page")),
                                    List.of("path")),
                            this::document),
                    tool(
                            "list_operations",
                            "Endpoints of the OpenAPI documents: method, path, operationId, summary, tags. Narrow"
                                    + " with 'spec' (e.g. 'api-studio', 'api-public', 'api-partner', 'auth-public')"
                                    + " and 'query' (part of a path, id, summary or tag).",
                            schema(
                                    Map.of(
                                            "spec", stringProp("Optional OpenAPI document name"),
                                            "query", stringProp("Optional filter, e.g. 'listings'")),
                                    List.of()),
                            args -> {
                                var operations = docs.operations(
                                        nullableArg(args, "spec"), nullableArg(args, "query"));
                                return Map.of(
                                        "count",
                                        operations.size(),
                                        "operations",
                                        operations.stream().limit(200).toList());
                            }),
                    tool(
                            "get_operation",
                            "One endpoint's parameters, request body and responses with every schema inlined. Name"
                                    + " it by operationId, or by method and path; 'spec' picks the document when"
                                    + " several have it.",
                            schema(
                                    Map.of(
                                            "operationId", stringProp("e.g. 'listingUpdatePriceAndStock'"),
                                            "method", stringProp("e.g. 'PATCH'"),
                                            "path", stringProp("e.g. '/api/v1/merchants/{merchantId}/listings'"),
                                            "spec", stringProp("Optional OpenAPI document name")),
                                    List.of()),
                            args -> docs.operation(
                                            nullableArg(args, "spec"),
                                            nullableArg(args, "operationId"),
                                            nullableArg(args, "method"),
                                            nullableArg(args, "path"))
                                    .<Object>map(op -> op)
                                    .orElseThrow(() -> new IllegalArgumentException(
                                            "No such operation. list_operations shows what exists."))));
        }

        List<SyncResourceSpecification> resources() {
            var resources = new ArrayList<SyncResourceSpecification>();
            for (var doc : docs.documents()) {
                var uri = DOC_SCHEME + doc.path();
                resources.add(new SyncResourceSpecification(
                        McpSchema.Resource.builder()
                                .uri(uri)
                                .name(doc.path())
                                .title(doc.title())
                                .mimeType("text/markdown")
                                .size((long) doc.text().length())
                                .build(),
                        (_, _) -> new ReadResourceResult(
                                List.of(new TextResourceContents(uri, "text/markdown", doc.text())))));
            }
            for (var spec : docs.specs()) {
                var uri = SPEC_SCHEME + spec.name() + ".yaml";
                resources.add(new SyncResourceSpecification(
                        McpSchema.Resource.builder()
                                .uri(uri)
                                .name(spec.name() + ".yaml")
                                .title("OpenAPI: " + spec.name())
                                .mimeType("application/yaml")
                                .size((long) spec.yaml().length())
                                .build(),
                        (_, _) -> new ReadResourceResult(
                                List.of(new TextResourceContents(uri, "application/yaml", spec.yaml())))));
            }
            return resources;
        }

        private Object document(Map<String, Object> args) {
            var path = stringArg(args, "path", "");
            var doc = docs.document(path)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No document " + path + ". search_docs or list_documents shows the paths."));
            var section = nullableArg(args, "section");
            if (section != null) {
                var wanted = section.strip();
                return doc.sections().stream()
                        .filter(s -> s.heading().equalsIgnoreCase(wanted)
                                || s.anchor().equals(DevDocs.anchor(wanted))
                                || s.anchor().equals(wanted.replaceFirst("^#", "")))
                        .findFirst()
                        .<Object>map(s -> Map.of(
                                "path", doc.path(), "heading", s.heading(), "anchor", s.anchor(), "text", s.text()))
                        .orElseThrow(() -> new IllegalArgumentException("No section " + wanted + " in " + doc.path()
                                + ". Its headings: "
                                + doc.sections().stream()
                                        .map(DevDocs.Section::heading)
                                        .toList()));
            }
            var offset = Math.max(0, Math.min(intArg(args, "offset", 0), doc.text().length()));
            var end = Math.min(doc.text().length(), offset + props.pageChars());
            var page = new LinkedHashMap<String, Object>();
            page.put("path", doc.path());
            page.put("title", doc.title());
            page.put("length", doc.text().length());
            page.put("offset", offset);
            page.put("text", doc.text().substring(offset, end));
            if (end < doc.text().length()) {
                page.put("nextOffset", end);
            }
            return page;
        }

        private SyncToolSpecification tool(
                String name, String description, Map<String, Object> inputSchema, Function<Map<String, Object>, Object> body) {
            return new SyncToolSpecification(
                    McpSchema.Tool.builder()
                            .name(name)
                            .description(description)
                            .inputSchema(inputSchema)
                            .annotations(new McpSchema.ToolAnnotations(null, true, false, true, false, null))
                            .build(),
                    (_, request) -> {
                        var args = request.arguments() == null ? Map.<String, Object>of() : request.arguments();
                        try {
                            return CallToolResult.builder()
                                    .addTextContent(json.writeValueAsString(body.apply(args)))
                                    .isError(false)
                                    .build();
                        } catch (IllegalArgumentException e) {
                            return CallToolResult.builder()
                                    .addTextContent(String.valueOf(e.getMessage()))
                                    .isError(true)
                                    .build();
                        }
                    });
        }

        private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
            return Map.of(
                    "type", "object", "properties", properties, "required", required, "additionalProperties", false);
        }

        private static Map<String, Object> stringProp(String description) {
            return Map.of("type", "string", "description", description);
        }

        private static Map<String, Object> intProp(String description) {
            return Map.of("type", "integer", "minimum", 0, "description", description);
        }

        private static String stringArg(Map<String, Object> args, String name, String fallback) {
            var value = nullableArg(args, name);
            return value == null ? fallback : value;
        }

        private static @Nullable String nullableArg(Map<String, Object> args, String name) {
            var value = args.get(name);
            return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
        }

        private static int intArg(Map<String, Object> args, String name, int fallback) {
            var value = args.get(name);
            if (value instanceof Number n) {
                return n.intValue();
            }
            try {
                return value == null ? fallback : Integer.parseInt(String.valueOf(value).strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a whole number", e);
            }
        }
    }
}
