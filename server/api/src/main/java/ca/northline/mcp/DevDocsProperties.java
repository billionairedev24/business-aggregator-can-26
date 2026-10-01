package ca.northline.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.devdocs.*}: the developer docs MCP server (S-128, docs/runbooks/mcp.md § Developer docs).
 *
 * @param enabled serve {@code /mcp/docs} at all
 * @param access {@code open} — anyone who reaches it, no token (local); {@code staff} — a token issued for
 *     {@link #resource()} to Northline staff with a second factor (the cloud profiles)
 * @param resource the docs server's canonical URI (RFC 8707): token audience and resource metadata
 * @param searchResults how many hits {@code search_docs} returns at most
 * @param pageChars how much of a document {@code get_document} returns per call ({@code offset} pages through it)
 */
@ConfigurationProperties("northline.devdocs")
public record DevDocsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("open") Access access,
        @DefaultValue("http://localhost:8080/mcp/docs") String resource,
        @DefaultValue("8") int searchResults,
        @DefaultValue("24000") int pageChars) {

    public enum Access {
        OPEN,
        STAFF
    }
}
