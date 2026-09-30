package ca.northline.auth.dpop;

import ca.northline.auth.clients.RegisteredClients;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Client authentication for the mobile apps (S-29) beyond the code exchange. Spring Authorization Server authenticates
 * a public client only by PKCE, i.e. when it exchanges a code; the apps also need to
 *
 * <ul>
 *   <li><b>refresh</b> ({@code grant_type=refresh_token}): RFC 9449 § 5 lets a public client refresh when its refresh
 *       token is bound to its DPoP key, which the refresh grant then checks (the proof's key must match the
 *       {@code cnf.jkt} of the authorization's access token). A request with a {@code client_id}, no other client
 *       credentials and a {@code DPoP} proof authenticates a {@code dpop-required} public client; the proof is
 *       verified by {@link DpopTokenEndpointFilter} and again by the grant.
 *   <li><b>sign out</b> ({@code POST /oauth2/revoke} with {@code client_id} and the refresh token, RFC 7009 § 2.1): the
 *       token itself is the proof, and revoking can only end the holder's own sign-in (S-20: the whole sign-in ends).
 * </ul>
 *
 * Nothing else (introspection, other grants) is open to public clients.
 */
public final class AppClientAuthentication {

    private static final String PURPOSE = "northline.public-client-request";
    private static final String REFRESH = "refresh";
    private static final String REVOKE = "revoke";

    private AppClientAuthentication() {}

    /** Reads the request; {@code null} when it isn't one of the two (the other converters then try). */
    public static AuthenticationConverter converter() {
        return AppClientAuthentication::convert;
    }

    /** Authenticates what {@link #converter()} read; {@code null} for anything else. */
    public static AuthenticationProvider provider(RegisteredClientRepository clients) {
        return new Provider(clients);
    }

    private static @Nullable Authentication convert(HttpServletRequest request) {
        var purpose = purpose(request);
        if (purpose == null
                || request.getHeader(HttpHeaders.AUTHORIZATION) != null
                || request.getParameter(OAuth2ParameterNames.CLIENT_SECRET) != null
                || request.getParameter(OAuth2ParameterNames.CLIENT_ASSERTION) != null) {
            return null;
        }
        var clientIds = request.getParameterValues(OAuth2ParameterNames.CLIENT_ID);
        if (clientIds == null || clientIds.length != 1 || clientIds[0].isBlank()) {
            return null;
        }
        var parameters = new HashMap<String, Object>();
        request.getParameterMap().forEach((name, values) -> {
            if (!OAuth2ParameterNames.CLIENT_ID.equals(name)) {
                parameters.put(name, values.length == 1 ? values[0] : values);
            }
        });
        parameters.put(PURPOSE, purpose);
        return new OAuth2ClientAuthenticationToken(clientIds[0], ClientAuthenticationMethod.NONE, null, parameters);
    }

    private static @Nullable String purpose(HttpServletRequest request) {
        if ("/oauth2/revoke".equals(request.getRequestURI())) {
            return REVOKE;
        }
        var refresh = AuthorizationGrantType.REFRESH_TOKEN
                .getValue()
                .equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE));
        return refresh && request.getHeader(DpopTokenEndpointFilter.PROOF_HEADER) != null ? REFRESH : null;
    }

    private record Provider(RegisteredClientRepository clients) implements AuthenticationProvider {

        @Override
        public @Nullable Authentication authenticate(Authentication authentication) {
            var request = (OAuth2ClientAuthenticationToken) authentication;
            if (!ClientAuthenticationMethod.NONE.equals(request.getClientAuthenticationMethod())
                    || request.getAdditionalParameters().get(PURPOSE) == null) {
                return null; // e.g. PKCE at the code exchange: Spring's PublicClientAuthenticationProvider
            }
            var client = clients.findByClientId(String.valueOf(request.getPrincipal()));
            if (client == null || !RegisteredClients.isPublic(client) || !RegisteredClients.dpopRequired(client)) {
                throw new OAuth2AuthenticationException(new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_CLIENT, "Client authentication failed: client_id", null));
            }
            return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
        }
    }
}
