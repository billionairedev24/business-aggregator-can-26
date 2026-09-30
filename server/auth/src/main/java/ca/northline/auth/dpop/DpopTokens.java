package ca.northline.auth.dpop;

import ca.northline.auth.clients.RegisteredClients;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * Token generation pieces for DPoP-bound tokens, used by the auth server's token generator (S-29).
 *
 * <ul>
 *   <li>{@link #confirmation(JwtEncodingContext)}: {@code cnf.jkt} = the proof key's RFC 7638 thumbprint on access
 *       tokens issued with a DPoP proof. Spring adds it only when the application declares no token generator of its
 *       own; this one does (for the refresh tokens below), so it adds the claim itself, the same way.
 *   <li>{@link #refreshTokens(Clock)}: Spring never issues refresh tokens to public clients. A {@code dpop-required}
 *       public client gets one when the request carried a DPoP proof — the refresh token is then bound to that key
 *       through the authorization's access token, which every refresh must match (RFC 9449 § 5). Other clients get
 *       Spring's behaviour (96 random bytes, the client's refresh TTL).
 * </ul>
 */
public final class DpopTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private DpopTokens() {}

    /** Adds {@code cnf.jkt} to an access token requested with a DPoP proof. */
    public static void confirmation(JwtEncodingContext context) {
        Jwt proof = context.get(OAuth2TokenContext.DPOP_PROOF_KEY);
        if (proof == null || !OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }
        String thumbprint;
        try {
            @SuppressWarnings("unchecked")
            var jwk = JWK.parse((Map<String, Object>) proof.getHeaders().get("jwk"));
            thumbprint = jwk.computeThumbprint().toString();
        } catch (JOSEException | ParseException | RuntimeException e) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(OAuth2ErrorCodes.INVALID_DPOP_PROOF, "jwk header is missing or invalid.", null), e);
        }
        var cnf = new HashMap<String, Object>();
        cnf.put("jkt", thumbprint);
        context.getClaims().claim("cnf", cnf);
    }

    /** Refresh tokens: Spring's rules, plus DPoP-bound ones for public clients. */
    public static OAuth2TokenGenerator<OAuth2RefreshToken> refreshTokens(Clock clock) {
        return context -> generate(context, clock);
    }

    private static @Nullable OAuth2RefreshToken generate(OAuth2TokenContext context, Clock clock) {
        if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
            return null;
        }
        var client = context.getRegisteredClient();
        if (RegisteredClients.isPublic(client)
                && (!RegisteredClients.dpopRequired(client)
                        || context.get(OAuth2TokenContext.DPOP_PROOF_KEY) == null)) {
            return null; // a public client's refresh token must be sender-constrained
        }
        var bytes = new byte[96];
        RANDOM.nextBytes(bytes);
        var issuedAt = clock.instant();
        return new OAuth2RefreshToken(
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                issuedAt,
                issuedAt.plus(client.getTokenSettings().getRefreshTokenTimeToLive()));
    }
}
