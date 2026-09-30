package ca.northline.auth.replay;

import java.time.Duration;

/**
 * State that single-use checks share across auth instances: ids that may be used once (DPoP proof {@code jti}s, client
 * assertion {@code jti}s) and values shared by every instance for a while (DPoP nonces).
 */
public interface ReplayStore {

    /** Records {@code key} for {@code ttl}; false when it was already recorded (a replay). */
    boolean firstUse(String key, Duration ttl);

    /** The value stored under {@code key}, created at random by whichever instance asks first; kept for {@code ttl}. */
    String shared(String key, Duration ttl);

    /** The store can't be reached: a single-use check can't be made, so the caller refuses the request (fail closed). */
    final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Unavailable(Throwable cause) {
            super("Replay store unavailable", cause);
        }
    }
}
