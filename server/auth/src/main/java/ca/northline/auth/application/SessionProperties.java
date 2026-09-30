package ca.northline.auth.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.auth.sessions.*} (S-19, docs/runbooks/README.md § Sessions).
 *
 * @param idleTimeout a session without activity (auth server requests or token refreshes) for this long, and without a
 *     live refresh token, is no longer listed as active — the auth server's own idle timeout
 * @param stepUpMaxAge revoking sessions or removing a passkey needs a second factor used at most this long ago
 * @param touchInterval "last seen" is written at most this often per session
 */
@ConfigurationProperties("northline.auth.sessions")
public record SessionProperties(
        @DefaultValue("12h") Duration idleTimeout,
        @DefaultValue("10m") Duration stepUpMaxAge,
        @DefaultValue("1m") Duration touchInterval) {}
