package ca.northline.auth.clients;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * One client under {@code northline.oauth.clients.<client-id>} (the map key is the OAuth {@code client_id}).
 *
 * @param type {@code confidential} (a BFF: {@code client_secret_basic}) or {@code public} (a mobile app: no secret)
 * @param optional when true and a confidential client has no {@code secret-hash}, it is skipped (logged) instead of
 *     failing start-up — for clients whose app is not deployed in every environment yet
 * @param name display name (default: the client id)
 * @param secretHash confidential only: the <em>encoded</em> secret ({@code {bcrypt}…}) from the secrets manager
 *     ({@code *_BFF_SECRET_HASH}); never the plain secret. Changing it rotates the secret.
 * @param grantTypes {@code authorization_code}, {@code refresh_token}, {@code client_credentials}
 * @param dpopRequired placeholder for the mobile clients (ARCHITECTURE.md § Identity): stored in the client settings as
 *     {@value #DPOP_REQUIRED}, not enforced yet
 */
record ClientSpec(
        @Nullable Type type,
        @DefaultValue("false") boolean optional,
        @Nullable String name,
        @Nullable String secretHash,
        @DefaultValue List<String> redirectUris,
        @DefaultValue List<String> postLogoutRedirectUris,
        @DefaultValue List<String> scopes,

        @DefaultValue({"authorization_code", "refresh_token"})
        List<String> grantTypes,

        @DefaultValue("true") boolean requirePkce,
        @DefaultValue("false") boolean requireConsent,
        @DefaultValue("10m") Duration accessTokenTtl,
        @DefaultValue("12h") Duration refreshTokenTtl,
        @DefaultValue("false") boolean dpopRequired) {

    /** Client setting key of the DPoP placeholder. */
    static final String DPOP_REQUIRED = "settings.client.northline.dpop-required";

    private static final Pattern CLIENT_ID = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,99}");
    private static final Pattern ENCODED = Pattern.compile("\\{[a-z0-9-]+}.+");
    private static final List<String> GRANT_TYPES =
            List.of("authorization_code", "refresh_token", "client_credentials");

    /** Confidential or public (RFC 6749 § 2.1). */
    enum Type {
        CONFIDENTIAL,
        PUBLIC
    }

    /** A confidential client marked optional whose secret isn't set: not registered in this environment. */
    boolean notConfigured() {
        return optional && type == Type.CONFIDENTIAL && !StringUtils.hasText(secretHash);
    }

    /** Every configuration problem of this client (empty = valid). */
    List<String> problems(String clientId, ClientPolicy policy) {
        var problems = new ArrayList<String>();
        if (!CLIENT_ID.matcher(clientId).matches()) {
            problems.add("client id must be lower-case letters, digits and . _ : - (at most 100)");
        }
        if (type == null) {
            problems.add("type is required: confidential | public");
            return problems;
        }
        grantTypes.stream()
                .filter(g -> !GRANT_TYPES.contains(g))
                .forEach(g -> problems.add("unknown grant type " + g + " (allowed: " + GRANT_TYPES + ")"));
        if (grantTypes.isEmpty()) {
            problems.add("grant-types is empty");
        }
        if (scopes.isEmpty()) {
            problems.add("scopes is empty");
        }
        problems.addAll(
                switch (type) {
                    case CONFIDENTIAL -> secretProblems(clientId, policy);
                    case PUBLIC -> publicClientProblems();
                });
        if (grantTypes.contains("authorization_code")) {
            if (redirectUris.isEmpty()) {
                problems.add("redirect-uris is required for authorization_code");
            }
            if (!requirePkce && (type == Type.PUBLIC || policy == ClientPolicy.STRICT)) {
                problems.add("require-pkce must stay true (OAuth 2.1)");
            }
        }
        redirectUris.forEach(uri -> check(problems, "redirect URI", uri, policy));
        postLogoutRedirectUris.forEach(uri -> check(problems, "post-logout redirect URI", uri, policy));
        if (accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            problems.add("access-token-ttl must be positive");
        }
        if (grantTypes.contains("refresh_token") && refreshTokenTtl.compareTo(accessTokenTtl) <= 0) {
            problems.add("refresh-token-ttl must be longer than access-token-ttl");
        }
        return problems;
    }

    private List<String> secretProblems(String clientId, ClientPolicy policy) {
        if (!StringUtils.hasText(secretHash)) {
            return List.of("secret-hash is required for a confidential client (the {bcrypt}… value from the secrets"
                    + " manager, e.g. " + clientId.toUpperCase(Locale.ROOT).replace('-', '_') + "_SECRET_HASH)");
        }
        if (!ENCODED.matcher(secretHash).matches()) {
            return List.of("secret-hash must be an encoded value such as {bcrypt}…, never the plain secret");
        }
        if (secretHash.startsWith("{noop}") && !policy.allowsPlainSecrets()) {
            return List.of("secret-hash {noop}… (unhashed) is not allowed under staging/prod: use {bcrypt}…");
        }
        return List.of();
    }

    private List<String> publicClientProblems() {
        var problems = new ArrayList<String>();
        if (StringUtils.hasText(secretHash)) {
            problems.add("a public client has no secret: remove secret-hash");
        }
        if (grantTypes.contains("client_credentials")) {
            problems.add("a public client cannot use client_credentials");
        }
        return problems;
    }

    private void check(List<String> problems, String what, String uri, ClientPolicy policy) {
        var reason = policy.rejectRedirect(uri, type == null ? Type.CONFIDENTIAL : type);
        if (reason != null) {
            problems.add(what + " " + uri + " " + reason);
        }
    }

    /**
     * The registration this configuration asks for. {@code existing} (same client id, already stored) keeps its
     * internal id and issue date, so saving it updates the row in place.
     */
    RegisteredClient toRegisteredClient(String clientId, @Nullable RegisteredClient existing) {
        var builder =
                RegisteredClient.withId(existing == null ? UUID.randomUUID().toString() : existing.getId());
        var issuedAt = existing == null ? null : existing.getClientIdIssuedAt();
        if (issuedAt != null) {
            builder.clientIdIssuedAt(issuedAt);
        }
        builder.clientId(clientId)
                .clientName(StringUtils.hasText(name) ? name : clientId)
                .redirectUris(uris -> uris.addAll(redirectUris))
                .postLogoutRedirectUris(uris -> uris.addAll(postLogoutRedirectUris))
                .scopes(s -> s.addAll(scopes))
                .authorizationGrantTypes(g -> grantTypes.forEach(t -> g.add(new AuthorizationGrantType(t))))
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(requirePkce)
                        .requireAuthorizationConsent(requireConsent)
                        .settings(s -> s.putAll(Map.of(DPOP_REQUIRED, dpopRequired)))
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(accessTokenTtl)
                        .refreshTokenTimeToLive(refreshTokenTtl)
                        .reuseRefreshTokens(false) // rotating refresh tokens
                        .idTokenSignatureAlgorithm(SignatureAlgorithm.ES256)
                        .build());
        if (type == Type.PUBLIC) {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        } else {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .clientSecret(Objects.requireNonNull(secretHash, "validated by problems()"));
        }
        return builder.build();
    }
}
