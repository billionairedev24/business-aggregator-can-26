package ca.northline.auth.clients;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/** How the rest of the auth server reads the Northline settings a {@link ClientSpec} stored on a registration. */
public final class RegisteredClients {

    /** Client setting: the client must prove possession of a DPoP key (RFC 9449) at the token endpoint (S-29). */
    public static final String DPOP_REQUIRED = "settings.client.northline.dpop-required";

    /** Client settings of a partner (S-30): the businesses it acts for, whether it is revoked, its registered keys. */
    public static final String PARTNER_MERCHANTS = "settings.client.northline.partner.merchants";

    public static final String PARTNER_REVOKED = "settings.client.northline.partner.revoked";
    public static final String PARTNER_PUBLIC_KEYS = "settings.client.northline.partner.public-keys";

    private RegisteredClients() {}

    /** A partner ({@code partner:<name>}, client credentials with {@code private_key_jwt}). */
    public static boolean isPartner(RegisteredClient client) {
        return client.getClientId().startsWith(PartnerSpec.CLIENT_ID_PREFIX)
                && client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.PRIVATE_KEY_JWT);
    }

    /** The businesses a partner acts for (the {@code merchants} claim of its tokens). */
    public static List<String> partnerMerchants(RegisteredClient client) {
        return client.getClientSettings().getSetting(PARTNER_MERCHANTS) instanceof List<?> ids
                ? ids.stream().map(String::valueOf).toList()
                : List.of();
    }

    /** A revoked partner gets no new token. */
    public static boolean partnerRevoked(RegisteredClient client) {
        return Boolean.TRUE.equals(client.getClientSettings().getSetting(PARTNER_REVOKED));
    }

    /** The partner's registered public keys (a JWK Set JSON), when it doesn't publish a JWK Set URL. */
    public static @Nullable String partnerPublicKeys(RegisteredClient client) {
        return client.getClientSettings().getSetting(PARTNER_PUBLIC_KEYS);
    }

    /** Every token request of this client needs a DPoP proof; its tokens are then bound to that key. */
    public static boolean dpopRequired(RegisteredClient client) {
        return Boolean.TRUE.equals(client.getClientSettings().getSetting(DPOP_REQUIRED));
    }

    /** A public client (a mobile app): no secret, authenticated by PKCE and, for refreshes, by its DPoP key. */
    public static boolean isPublic(RegisteredClient client) {
        return client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE);
    }
}
