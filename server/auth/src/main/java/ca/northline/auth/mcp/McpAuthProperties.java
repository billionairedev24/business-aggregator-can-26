package ca.northline.auth.mcp;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.auth.mcp.*} (S-127, docs/runbooks/mcp.md).
 *
 * @param resources the resource indicators (RFC 8707) a token may be requested for: the MCP server's canonical URI
 *     ({@code ${API_PUBLIC_URL}/mcp}) and the developer docs server ({@code …/mcp/docs}). Anything else is
 *     {@code invalid_target}.
 * @param metadataDocuments OAuth Client ID Metadata Documents: an agent whose {@code client_id} is an HTTPS URL is
 *     registered from the JSON document at that URL
 */
@ConfigurationProperties("northline.auth.mcp")
public record McpAuthProperties(@DefaultValue List<String> resources, MetadataDocuments metadataDocuments) {

    /**
     * @param enabled accept URL client ids at all
     * @param scopes what such a client may be granted (the person still consents to each)
     * @param accessTokenTtl lifetime of its access tokens (public clients get no refresh token without DPoP, S-29)
     * @param cacheFor how long a fetched document is trusted before it is fetched again
     * @param timeout connect + read time-out of the fetch
     * @param maxBytes largest document accepted
     * @param allowedHosts if not empty, only documents on these hosts (e.g. {@code claude.ai}); empty = any HTTPS host
     *     with a public address
     * @param allowInsecure {@code http://} and private addresses — tests and {@code local} only
     */
    public record MetadataDocuments(
            @DefaultValue("true") boolean enabled,

            @DefaultValue({"openid", "profile", "merchant", "mcp", "mcp.write"})
            List<String> scopes,

            @DefaultValue("1h") Duration accessTokenTtl,
            @DefaultValue("10m") Duration cacheFor,
            @DefaultValue("5s") Duration timeout,
            @DefaultValue("5120") int maxBytes,
            @DefaultValue List<String> allowedHosts,
            @DefaultValue("false") boolean allowInsecure) {}
}
