package ca.northline.auth.dpop;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.auth.dpop.*} (S-29).
 *
 * @param store where used proof ids ({@code jti}) and the server nonces live: {@code redis} (Valkey, shared by every
 *     instance; default and required under staging/prod) or {@code memory} (one instance: local runs and tests)
 * @param nonceLifetime how long a {@code DPoP-Nonce} is accepted: each window has one nonce, and the previous window's
 *     is still accepted, so a nonce lives between one and two windows
 */
@ConfigurationProperties("northline.auth.dpop")
public record DpopProperties(
        @DefaultValue("redis") Store store,
        @DefaultValue("5m") Duration nonceLifetime) {

    /** Where the DPoP state lives. */
    public enum Store {
        REDIS,
        MEMORY
    }
}
