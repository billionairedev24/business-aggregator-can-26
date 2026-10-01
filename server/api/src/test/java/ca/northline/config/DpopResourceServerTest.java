package ca.northline.config;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-29: the api as resource server for the mobile apps' DPoP-bound tokens. Tokens are signed here with a stand-in for
 * northline-auth's key (the decoder trusts it); each app key is a freshly generated P-256 key.
 */
@Import(DpopResourceServerTest.IssuerKey.class)
class DpopResourceServerTest extends IntegrationTest {

    private static final ECKey ISSUER = key();
    private static final String URL = "http://localhost/api/v1/me/businesses";

    @TestConfiguration(proxyBeanMethods = false)
    static class IssuerKey {
        @Bean
        @Primary
        JwtDecoder testIssuerDecoder() {
            return NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(new JWKSet(ISSUER.toPublicJWK())))
                    .jwsAlgorithm(SignatureAlgorithm.ES256)
                    .build();
        }
    }

    private static ECKey key() {
        try {
            return new ECKeyGenerator(Curve.P_256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String thumbprint(ECKey key) throws JOSEException {
        return key.toPublicJWK().computeThumbprint().toString();
    }

    /** An access token as northline-auth issues it to an app; {@code jkt} = null for a bearer (BFF) token. */
    private static String accessToken(String userId, String scope, @Nullable String jkt) throws JOSEException {
        var claims = new JWTClaimsSet.Builder()
                .issuer("http://auth.invalid")
                .subject(userId)
                .audience(List.of("mobile-consumer", "northline-api"))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("scope", scope)
                .claim("acr", "mfa");
        if (jkt != null) {
            claims.claim("cnf", Map.of("jkt", jkt));
        }
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims.build());
        jwt.sign(new ECDSASigner(ISSUER));
        return jwt.serialize();
    }

    private static String proof(ECKey key, String method, String uri, @Nullable String accessToken) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .claim("htm", method)
                .claim("htu", uri)
                .issueTime(Date.from(Instant.now()));
        if (accessToken != null) {
            claims.claim(
                    "ath",
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(MessageDigest.getInstance("SHA-256")
                                    .digest(accessToken.getBytes(StandardCharsets.US_ASCII))));
        }
        var header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(new JOSEObjectType("dpop+jwt"))
                .jwk(key.toPublicJWK())
                .build();
        var jwt = new SignedJWT(header, claims.build());
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }

    private static MockHttpServletRequestBuilder dpop(String token, @Nullable String proof) {
        var request = get(URL).header("Authorization", "DPoP " + token);
        return proof == null ? request : request.header("DPoP", proof);
    }

    @Test
    void aDpopToken_withAProofOfItsKey_isAccepted() throws Exception {
        var app = key();
        var token = accessToken(data.user("Amara Osei"), "openid orders", thumbprint(app));
        mvc.perform(dpop(token, proof(app, "GET", URL, token))).andExpect(status().isOk());
    }

    @Test
    void aDpopToken_withoutAProof_is401() throws Exception {
        var app = key();
        var token = accessToken(data.user("Amara Osei"), "openid orders", thumbprint(app));
        mvc.perform(dpop(token, null))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("DPoP")));
    }

    @Test
    void aDpopToken_sentAsABearerToken_is401() throws Exception {
        var app = key();
        var token = accessToken(data.user("Amara Osei"), "openid orders", thumbprint(app));
        mvc.perform(get(URL).header("Authorization", "Bearer " + token).header("DPoP", proof(app, "GET", URL, token)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer error=\"invalid_token\"")));
    }

    @Test
    void aReplayedProof_is401() throws Exception {
        var app = key();
        var token = accessToken(data.user("Amara Osei"), "openid orders", thumbprint(app));
        var proof = proof(app, "GET", URL, token);
        mvc.perform(dpop(token, proof)).andExpect(status().isOk());
        mvc.perform(dpop(token, proof)).andExpect(status().isUnauthorized());
    }

    @Test
    void aProofFromAnotherKey_is401() throws Exception {
        var app = key();
        var token = accessToken(data.user("Amara Osei"), "openid orders", thumbprint(app));
        mvc.perform(dpop(token, proof(key(), "GET", URL, token))).andExpect(status().isUnauthorized());
    }

    @Test
    void aProofForAnotherTokenUrlOrMethod_is401() throws Exception {
        var app = key();
        var user = data.user("Amara Osei");
        var token = accessToken(user, "openid orders", thumbprint(app));
        var other = accessToken(user, "openid orders", thumbprint(app));
        mvc.perform(dpop(token, proof(app, "GET", URL, other))).andExpect(status().isUnauthorized());
        mvc.perform(dpop(token, proof(app, "GET", "http://localhost/api/v1/me", token)))
                .andExpect(status().isUnauthorized());
        mvc.perform(dpop(token, proof(app, "POST", URL, token))).andExpect(status().isUnauthorized());
        mvc.perform(dpop(token, proof(app, "GET", URL, null))).andExpect(status().isUnauthorized());
    }

    @Test
    void aBearerToken_withoutCnf_stillWorks() throws Exception {
        var token = accessToken(data.user("Ravi Sandhu"), "openid profile merchant", null);
        mvc.perform(get(URL).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void anAppToken_neverReachesMerchantEndpoints_evenWithMfa() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        var app = key();
        var token = accessToken(biz.userId(), "openid profile orders bookings offline_access", thumbprint(app));
        var url = "http://localhost/api/v1/merchants/" + biz.merchantId();
        mvc.perform(get(url).header("Authorization", "DPoP " + token).header("DPoP", proof(app, "GET", url, token)))
                .andExpect(status().isForbidden());
    }
}
