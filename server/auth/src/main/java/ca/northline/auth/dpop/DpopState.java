package ca.northline.auth.dpop;

import java.time.Duration;

/**
 * State the DPoP checks share across instances: which proof ids were already used, and the nonce of each time window.
 */
interface DpopState {

    /** Records {@code key} for {@code ttl}; false when it was already recorded (a replayed proof). */
    boolean firstUse(String key, Duration ttl);

    /** The nonce of time window {@code window}, created by whichever instance asks first; kept for {@code ttl}. */
    String nonce(long window, Duration ttl);

    /** The store can't be reached: the proof can't be checked for replay, so the request is refused (fail closed). */
    final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unavailable(Throwable cause) {
            super("DPoP state store unavailable", cause);
        }
    }
}
