package ca.northline.auth.mcp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

/**
 * Resource indicators (RFC 8707) as the MCP specification requires them: an MCP client names the server it wants a
 * token for ({@code resource}) in the authorization request and again in the token request; the access token is then
 * addressed to that server ({@code aud}), next to the api. Only the configured resources are accepted
 * ({@code invalid_target} otherwise), so no token can be minted for someone else's server.
 */
public final class ResourceIndicators {

    public static final String PARAMETER = "resource";
    public static final String INVALID_TARGET = "invalid_target";

    private final Set<String> allowed;

    public ResourceIndicators(McpAuthProperties props) {
        this.allowed = Set.copyOf(props.resources());
    }

    /** The resources of a request's additional parameters (one value, or several), each checked. */
    public List<String> requested(@Nullable Map<String, Object> parameters) {
        if (parameters == null) {
            return List.of();
        }
        var value = parameters.get(PARAMETER);
        var values = switch (value) {
            case null -> List.<String>of();
            case String s -> List.of(s);
            case String[] a -> List.of(a);
            case List<?> l -> l.stream().map(String::valueOf).toList();
            default -> List.of(String.valueOf(value));
        };
        for (var resource : values) {
            if (!allowed.contains(resource)) {
                throw new OAuth2AuthenticationException(new OAuth2Error(
                        INVALID_TARGET,
                        "Unknown resource " + resource + " (this server issues tokens for " + allowed + ")",
                        "https://www.rfc-editor.org/rfc/rfc8707#section-2"));
            }
        }
        return values;
    }

    /**
     * Adds the requested resources to an access token's audience. The token request's {@code resource} wins; without
     * one, the authorization request's (RFC 8707 § 2.2: a token request may narrow, never widen, what was authorized).
     */
    public void addAudience(JwtEncodingContext context, List<String> audience) {
        var fromToken = context.getAuthorizationGrant() instanceof OAuth2AuthorizationGrantAuthenticationToken grant
                ? requested(grant.getAdditionalParameters())
                : List.<String>of();
        var authorization = context.getAuthorization();
        var request = authorization == null
                ? null
                : authorization.<OAuth2AuthorizationRequest>getAttribute(OAuth2AuthorizationRequest.class.getName());
        var fromAuthorization = request == null ? List.<String>of() : requested(request.getAdditionalParameters());
        if (!fromToken.isEmpty() && !fromAuthorization.isEmpty() && !fromAuthorization.containsAll(fromToken)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    INVALID_TARGET, "The token request names a resource the authorization didn't", null));
        }
        var resources = new LinkedHashSet<>(audience);
        resources.addAll(fromToken.isEmpty() ? fromAuthorization : fromToken);
        context.getClaims().audience(new ArrayList<>(resources));
    }
}
