package ca.northline.auth.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Google- or Apple-like OpenID provider on WireMock: JWK set, token endpoint answering with an RS256 ID token signed by
 * its own key, and (Google) a userinfo endpoint. Paths are prefixed ({@code /google/…}, {@code /apple/…}) so one WireMock
 * server hosts both; the issuer is {@code <base>/<name>}.
 */
public final class FakeOidcProvider {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WireMockServer server;
    private final String name;
    private final KeyPair keys;
    private final String kid;

    public FakeOidcProvider(WireMockServer server, String name) throws Exception {
        this.server = server;
        this.name = name;
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.keys = generator.generateKeyPair();
        this.kid = name + "-key-1";
    }

    public String issuer() {
        return server.baseUrl() + "/" + name;
    }

    public String url(String path) {
        return issuer() + path;
    }

    /** Publishes the JWK set; call after every WireMock reset. */
    public void publishKeys() {
        var pub = (RSAPublicKey) keys.getPublic();
        var jwk = Map.of(
                "kty",
                "RSA",
                "alg",
                "RS256",
                "use",
                "sig",
                "kid",
                kid,
                "n",
                b64(unsigned(pub.getModulus())),
                "e",
                b64(unsigned(pub.getPublicExponent())));
        server.stubFor(get(urlEqualTo("/" + name + "/keys"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(JSON.writeValueAsString(Map.of("keys", java.util.List.of(jwk))))));
    }

    /** The next code exchange answers with an ID token carrying these claims (plus iss, aud, iat, exp, nonce). */
    public void willIssue(String clientId, String nonce, Map<String, Object> claims) {
        var now = Instant.now();
        var all = new LinkedHashMap<String, Object>();
        all.put("iss", issuer());
        all.put("aud", clientId);
        all.put("iat", now.getEpochSecond());
        all.put("exp", now.plusSeconds(600).getEpochSecond());
        all.put("nonce", nonce);
        all.putAll(claims);
        var body = Map.of(
                "access_token", "provider-access-token",
                "token_type", "Bearer",
                "expires_in", 3600,
                "scope", "openid email profile",
                "id_token", sign(all));
        server.stubFor(post(urlEqualTo("/" + name + "/token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(JSON.writeValueAsString(body))));
        var userInfo = new LinkedHashMap<String, Object>(claims);
        server.stubFor(get(urlPathEqualTo("/" + name + "/userinfo"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(JSON.writeValueAsString(userInfo))));
    }

    private String sign(Map<String, Object> claims) {
        try {
            var header = Map.of("alg", "RS256", "kid", kid, "typ", "JWT");
            var input = b64(JSON.writeValueAsBytes(header)) + "." + b64(JSON.writeValueAsBytes(claims));
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keys.getPrivate());
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + b64(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] unsigned(BigInteger value) {
        var bytes = value.toByteArray();
        return bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
