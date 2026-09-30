package ca.northline.auth.partners;

import ca.northline.auth.clients.RegisteredClients;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

/**
 * A partner's access token (S-30): {@code sub} = its client id, addressed to the api, {@code scope} (space-delimited,
 * RFC 9068), {@code roles: [partner]} and {@code merchants} = the businesses it is bound to. The api lets a partner
 * token reach only endpoints marked for partners, for those businesses, with the right scope.
 */
public final class PartnerClaims {

    public static final String ROLE = "partner";

    private PartnerClaims() {}

    public static void add(JwtEncodingContext context, String apiAudience) {
        var client = context.getRegisteredClient();
        if (!RegisteredClients.isPartner(client) || !OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }
        context.getClaims()
                .audience(new ArrayList<>(List.of(client.getClientId(), apiAudience)))
                .claim("scope", String.join(" ", context.getAuthorizedScopes()))
                .claim("roles", new ArrayList<>(List.of(ROLE)))
                .claim("merchants", new ArrayList<>(RegisteredClients.partnerMerchants(client)));
    }
}
