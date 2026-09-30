package ca.northline.auth.dpop;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.auth.dpop.*} (S-29). Used proof ids and nonces live in the {@code ReplayStore}
 * ({@code northline.auth.replay.store}).
 *
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
