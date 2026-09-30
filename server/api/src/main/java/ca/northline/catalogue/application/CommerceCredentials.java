package ca.northline.catalogue.application;

import ca.northline.catalogue.application.CommerceCatalogSource.Credentials;
import ca.northline.catalogue.application.CommerceCatalogSource.GrantRevoked;
import ca.northline.catalogue.application.IntegrationRepository.Connection;
import ca.northline.shared.crypto.SecretSealer;
import ca.northline.shared.crypto.SecretSealer.Sealed;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The platform tokens at rest: access and refresh token sealed together with the api's envelope key
 * ({@link SecretSealer}, S-32), bound to the integration id. Opened only for a call; a token that is about to expire is
 * refreshed and re-sealed (Square and Lightspeed rotate refresh tokens). A refused grant puts the connection in
 * {@code reconnect}; callers then get nothing and stop.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class CommerceCredentials {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final IntegrationRepository integrations;
    private final CommerceSources sources;
    private final SecretSealer sealer;
    private final Clock clock;

    record Stored(
            String accessToken,
            @Nullable String refreshToken,
            @Nullable Instant expiresAt,
            String account) {}

    Sealed seal(Credentials c, String integrationId) {
        var json = JSON.writeValueAsString(new Stored(c.accessToken(), c.refreshToken(), c.expiresAt(), c.account()));
        return sealer.seal(json, integrationId);
    }

    Optional<Credentials> open(String integrationId) {
        return integrations.credentials(integrationId).map(box -> {
            var s = JSON.readValue(sealer.open(box, integrationId), Stored.class);
            return new Credentials(s.accessToken(), s.refreshToken(), s.expiresAt(), s.account());
        });
    }

    /** Runs {@code call} with fresh credentials; empty when the connection needs a reconnect (it is marked so). */
    <T> Optional<T> with(Connection connection, Function<Credentials, T> call) {
        if (!connection.connected() || connection.needsReconnect()) {
            return Optional.empty();
        }
        var id = connection.requiredId();
        try {
            var stored = open(id).orElseThrow(() -> new GrantRevoked("no credentials stored"));
            var fresh = sources.get(connection.provider()).refresh(stored);
            if (!fresh.equals(stored)) {
                integrations.replaceCredentials(id, seal(fresh, id));
            }
            return Optional.of(call.apply(fresh));
        } catch (GrantRevoked e) {
            integrations.markReconnect(id, e.getMessage() == null ? "revoked" : e.getMessage(), clock.instant());
            log.info(
                    "Commerce integration {} ({}) needs a reconnect: {}",
                    id,
                    connection.provider().code(),
                    e.getMessage());
            return Optional.empty();
        }
    }

    /** 256 random bits, base64url (OAuth state). */
    static String random() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
