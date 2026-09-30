package ca.northline.auth.partners;

import ca.northline.auth.clients.RegisteredClients;
import ca.northline.auth.replay.ReplayStore;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.authentication.JwtClientAssertionDecoderFactory;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.web.client.RestOperations;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Verifies a partner's client assertion ({@code private_key_jwt}, RFC 7523 § 3) in place of Spring Authorization
 * Server's decoder, which only knows JWK Set URLs and leaves replay and lifetime to the application:
 *
 * <ul>
 *   <li>keys from the partner's JWK Set URL (fetched with short time-outs and cached; an unknown {@code kid} refreshes
 *       it, so a rotation needs nothing here) or from its registered public keys — several at once during a rotation;
 *       ES256, RS256 or PS256;
 *   <li>{@code iss} = {@code sub} = the client id; {@code aud} = this issuer or its token endpoint;
 *   <li>{@code exp} and {@code iat} required, within 60 s of our clock, and the assertion lives at most 5 minutes;
 *   <li>{@code jti} required and single-use across every instance (Valkey, {@link ReplayStore});
 *   <li>a revoked partner fails ({@code invalid_client}) before any key is looked at.
 * </ul>
 *
 * Other clients (none today) keep Spring's decoder.
 */
public final class PartnerAssertions implements JwtDecoderFactory<RegisteredClient> {

    static final Duration SKEW = Duration.ofSeconds(60);
    static final Duration MAX_LIFETIME = Duration.ofMinutes(5);

    private record Cached(String keys, JwtDecoder decoder) {}

    private final ReplayStore replay;
    private final Clock clock;
    private final RestOperations rest;
    private final JwtDecoderFactory<RegisteredClient> others = new JwtClientAssertionDecoderFactory();
    private final Map<String, Cached> decoders = new ConcurrentHashMap<>();

    public PartnerAssertions(ReplayStore replay, Clock clock, RestOperations rest) {
        this.replay = replay;
        this.clock = clock;
        this.rest = rest;
    }

    @Override
    public JwtDecoder createDecoder(RegisteredClient client) {
        if (!RegisteredClients.isPartner(client)) {
            return others.createDecoder(client);
        }
        if (RegisteredClients.partnerRevoked(client)) {
            throw invalidClient("This partner is revoked.");
        }
        var url = client.getClientSettings().getJwkSetUrl();
        var keys = url != null ? "url:" + url : "keys:" + RegisteredClients.partnerPublicKeys(client);
        var cached = decoders.compute(
                client.getClientId(),
                (_, c) -> c != null && c.keys().equals(keys) ? c : new Cached(keys, build(client, url)));
        var decoder = Objects.requireNonNull(cached).decoder();
        return token -> {
            try {
                return decoder.decode(token);
            } catch (ReplayStore.Unavailable e) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("temporarily_unavailable", "Try again in a moment.", null), e);
            }
        };
    }

    private JwtDecoder build(RegisteredClient client, @Nullable String url) {
        Consumer<Set<SignatureAlgorithm>> algorithms =
                a -> a.addAll(List.of(SignatureAlgorithm.ES256, SignatureAlgorithm.RS256, SignatureAlgorithm.PS256));
        NimbusJwtDecoder decoder;
        if (url != null) {
            decoder = NimbusJwtDecoder.withJwkSetUri(url)
                    .jwsAlgorithms(algorithms)
                    .restOperations(rest)
                    .build();
        } else {
            JWKSet keys;
            try {
                keys = JWKSet.parse(Objects.requireNonNull(RegisteredClients.partnerPublicKeys(client), "public keys"));
            } catch (ParseException e) {
                throw invalidClient("The partner's registered keys can't be read.");
            }
            decoder = NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(keys))
                    .jwsAlgorithms(algorithms)
                    .build();
        }
        decoder.setJwtValidator(validator(client.getClientId()));
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> validator(String clientId) {
        var timestamps = new JwtTimestampValidator(SKEW);
        timestamps.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(
                new JwtClaimValidator<String>(JwtClaimNames.ISS, clientId::equals),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, clientId::equals),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, PartnerAssertions::forUs),
                timestamps,
                this::lifetime,
                jwt -> singleUse(clientId, jwt));
    }

    /** RFC 7523 § 3: the audience is this authorization server — its issuer or its token endpoint. */
    private static boolean forUs(List<String> audience) {
        var context = AuthorizationServerContextHolder.getContext();
        var issuer = context.getIssuer();
        var tokenEndpoint = UriComponentsBuilder.fromUriString(issuer)
                .path(context.getAuthorizationServerSettings().getTokenEndpoint())
                .build()
                .toUriString();
        return audience.contains(issuer) || audience.contains(tokenEndpoint);
    }

    private OAuth2TokenValidatorResult lifetime(Jwt jwt) {
        var now = clock.instant();
        Instant issuedAt = jwt.getIssuedAt();
        Instant expiresAt = jwt.getExpiresAt();
        if (issuedAt == null || expiresAt == null) {
            return failure("The assertion needs iat and exp.");
        }
        if (issuedAt.isAfter(now.plus(SKEW))
                || issuedAt.isBefore(now.minus(MAX_LIFETIME).minus(SKEW))) {
            return failure("The assertion's iat is too far from now.");
        }
        if (expiresAt.isAfter(now.plus(MAX_LIFETIME).plus(SKEW))) {
            return failure("The assertion may live at most 5 minutes.");
        }
        return OAuth2TokenValidatorResult.success();
    }

    /** Last: an assertion that fails any other check doesn't spend its id. */
    private OAuth2TokenValidatorResult singleUse(String clientId, Jwt jwt) {
        var jti = jwt.getId();
        var expiresAt = jwt.getExpiresAt();
        if (jti == null || jti.isBlank() || expiresAt == null) {
            return failure("The assertion needs a jti.");
        }
        var ttl = Duration.between(clock.instant(), expiresAt).plus(SKEW);
        return replay.firstUse("assertion-jti:" + sha256(clientId + ':' + jti), ttl.isNegative() ? SKEW : ttl)
                ? OAuth2TokenValidatorResult.success()
                : failure("This assertion was already used.");
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT, description, null));
    }

    private static OAuth2AuthenticationException invalidClient(String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT, description, null));
    }

    private static String sha256(String value) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
