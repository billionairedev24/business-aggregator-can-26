package ca.northline.auth.clients;

import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/** How the rest of the auth server reads the Northline settings a {@link ClientSpec} stored on a registration. */
public final class RegisteredClients {

    /** Client setting: the client must prove possession of a DPoP key (RFC 9449) at the token endpoint (S-29). */
    public static final String DPOP_REQUIRED = "settings.client.northline.dpop-required";

    private RegisteredClients() {}

    /** Every token request of this client needs a DPoP proof; its tokens are then bound to that key. */
    public static boolean dpopRequired(RegisteredClient client) {
        return Boolean.TRUE.equals(client.getClientSettings().getSetting(DPOP_REQUIRED));
    }

    /** A public client (a mobile app): no secret, authenticated by PKCE and, for refreshes, by its DPoP key. */
    public static boolean isPublic(RegisteredClient client) {
        return client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE);
    }
}
