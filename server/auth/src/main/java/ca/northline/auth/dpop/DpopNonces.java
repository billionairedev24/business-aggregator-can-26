package ca.northline.auth.dpop;

import ca.northline.auth.replay.ReplayStore;
import java.time.Clock;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Server nonces (RFC 9449 § 8): time is cut into windows of {@code nonce-lifetime}; each window has one random nonce
 * shared by every instance, and the current and previous windows' nonces are accepted. A client learns the current one
 * from the {@code DPoP-Nonce} header of any token-endpoint answer and puts it in its next proof.
 */
final class DpopNonces {

    private final ReplayStore state;
    private final Clock clock;
    private final Duration lifetime;

    DpopNonces(ReplayStore state, Clock clock, Duration lifetime) {
        this.state = state;
        this.clock = clock;
        this.lifetime = lifetime;
    }

    String current() {
        return state.shared(key(window()), lifetime.multipliedBy(3));
    }

    boolean accepts(@Nullable String nonce) {
        if (nonce == null || nonce.isBlank()) {
            return false;
        }
        var window = window();
        return nonce.equals(state.shared(key(window), lifetime.multipliedBy(3)))
                || nonce.equals(state.shared(key(window - 1), lifetime.multipliedBy(3)));
    }

    private static String key(long window) {
        return "dpop-nonce:" + window;
    }

    private long window() {
        return clock.millis() / lifetime.toMillis();
    }
}
