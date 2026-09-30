package ca.northline.auth.clients;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.util.StringUtils;

/**
 * One partner under {@code northline.oauth.partners.<name>} (S-30): a server-to-server integration that gets tokens
 * with {@code client_credentials}, authenticating with a JWT it signs with its own private key ({@code private_key_jwt},
 * RFC 7523). Registered as OAuth client {@code partner:<name>} (ARCHITECTURE.md § Identity: {@code partner:*}).
 *
 * @param name display name (default: the partner's key in configuration)
 * @param jwkSetUrl where the partner publishes its public keys (JWK Set). Rotation is the partner's: publish the new key
 *     next to the old one, switch, then drop the old one. Exclusive with {@code publicKeys}.
 * @param publicKeys the partner's public keys as JWK JSON (EC P-256 or RSA ≥ 2048, with {@code kid}), when it can't host
 *     a JWK Set. Several may be valid at once: add the new key, let the partner switch, remove the old one.
 * @param scopes what its tokens may do: {@code api.read}, {@code api.write} (design 05); a token asks for a subset
 * @param merchants the businesses ({@code merchants.merchants} ids) it acts for: its tokens carry them as
 *     {@code merchants} and the api refuses any other business
 * @param accessTokenTtl access token lifetime (design 05: 15 min; at most 1 h)
 * @param revoked true = no new token (the client authentication fails); tokens already issued end within
 *     {@code access-token-ttl}. Keep the entry: a partner removed from configuration is left as it was in the database.
 */
record PartnerSpec(
        @Nullable String name,
        @Nullable String jwkSetUrl,
        @DefaultValue List<String> publicKeys,
        @DefaultValue List<String> scopes,
        @DefaultValue List<String> merchants,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("false") boolean revoked) {

    /** Scopes a partner may hold (design 05: "api.read api.write (per merchant)"). */
    static final List<String> SCOPES = List.of("api.read", "api.write");

    static final String CLIENT_ID_PREFIX = "partner:";

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,49}");
    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");
    private static final Duration MAX_TTL = Duration.ofHours(1);

    static String clientId(String name) {
        return CLIENT_ID_PREFIX + name;
    }

    /** Every configuration problem of this partner (empty = valid). */
    List<String> problems(String name, ClientPolicy policy) {
        var problems = new ArrayList<String>();
        if (!NAME.matcher(name).matches()) {
            problems.add("partner name must be lower-case letters, digits and - (at most 50)");
        }
        var url = jwkSetUrl;
        var hasUrl = url != null && !url.isBlank();
        if (hasUrl == !publicKeys.isEmpty()) {
            problems.add("set exactly one of jwk-set-url and public-keys");
        }
        if (url != null && hasUrl) {
            var reason = policy.rejectRedirect(url, ClientSpec.Type.CONFIDENTIAL);
            if (reason != null) {
                problems.add("jwk-set-url " + url + " " + reason);
            }
        }
        var kids = new HashSet<String>();
        for (var i = 0; i < publicKeys.size(); i++) {
            var problem = keyProblem(publicKeys.get(i), kids);
            if (problem != null) {
                problems.add("public-keys[" + i + "] " + problem);
            }
        }
        if (scopes.isEmpty()) {
            problems.add("scopes is empty");
        }
        scopes.stream()
                .filter(s -> !SCOPES.contains(s))
                .forEach(s -> problems.add("scope " + s + " is not a partner scope (allowed: " + SCOPES + ")"));
        if (merchants.isEmpty()) {
            problems.add("merchants is empty: a partner acts for named businesses only");
        }
        merchants.stream()
                .filter(m -> !ULID.matcher(m).matches())
                .forEach(m -> problems.add("merchant " + m + " is not a business id (ULID)"));
        if (accessTokenTtl.isNegative() || accessTokenTtl.isZero() || accessTokenTtl.compareTo(MAX_TTL) > 0) {
            problems.add("access-token-ttl must be between 1 s and 1 h");
        }
        return problems;
    }

    private static @Nullable String keyProblem(String json, Set<String> kids) {
        JWK jwk;
        try {
            jwk = JWK.parse(json);
        } catch (ParseException _) {
            return "is not a JWK (JSON)";
        }
        if (jwk.isPrivate()) {
            return "contains a private key: register the public part only";
        }
        if (jwk.getKeyID() == null || !kids.add(jwk.getKeyID())) {
            return "needs its own kid";
        }
        return switch (jwk) {
            case ECKey ec when Curve.P_256.equals(ec.getCurve()) -> null;
            case RSAKey rsa when rsa.size() >= 2048 -> null;
            default -> "must be an EC P-256 or an RSA key of at least 2048 bits";
        };
    }

    /** The registration; {@code existing} keeps its internal id and issue date (an update in place). */
    RegisteredClient toRegisteredClient(String partner, @Nullable RegisteredClient existing) {
        var builder =
                RegisteredClient.withId(existing == null ? UUID.randomUUID().toString() : existing.getId());
        var issuedAt = existing == null ? null : existing.getClientIdIssuedAt();
        if (issuedAt != null) {
            builder.clientIdIssuedAt(issuedAt);
        }
        var settings = ClientSettings.builder()
                .requireProofKey(false)
                .requireAuthorizationConsent(false)
                .tokenEndpointAuthenticationSigningAlgorithm(signingAlgorithm())
                .setting(RegisteredClients.PARTNER_MERCHANTS, new ArrayList<>(merchants))
                .setting(RegisteredClients.PARTNER_REVOKED, revoked);
        var url = jwkSetUrl;
        if (url != null && !url.isBlank()) {
            settings.jwkSetUrl(url);
        } else {
            settings.setting(RegisteredClients.PARTNER_PUBLIC_KEYS, jwkSet().toString(true));
        }
        return builder.clientId(clientId(partner))
                .clientName(StringUtils.hasText(name) ? name : partner)
                .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scopes(s -> s.addAll(scopes))
                .clientSettings(settings.build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(accessTokenTtl)
                        .idTokenSignatureAlgorithm(SignatureAlgorithm.ES256)
                        .build())
                .build();
    }

    /** What the partner's metadata advertises; the assertion check accepts ES256, RS256 and PS256 from its keys. */
    private SignatureAlgorithm signingAlgorithm() {
        return !publicKeys.isEmpty() && jwkSet().getKeys().stream().allMatch(ECKey.class::isInstance)
                ? SignatureAlgorithm.ES256
                : SignatureAlgorithm.RS256;
    }

    private JWKSet jwkSet() {
        var keys = new ArrayList<JWK>();
        for (var json : publicKeys) {
            try {
                keys.add(JWK.parse(json).toPublicJWK());
            } catch (ParseException e) {
                throw new IllegalStateException("validated by problems()", e);
            }
        }
        return new JWKSet(keys);
    }
}
