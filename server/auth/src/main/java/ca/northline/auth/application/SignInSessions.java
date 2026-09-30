package ca.northline.auth.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-19): sessions = successful sign-ins ({@code identity.sessions}) and the OAuth authorizations issued
 * from them ({@code auth.authorization_sessions} → {@code auth.oauth2_authorization}: the BFF's and the apps' refresh
 * tokens).
 */
public interface SignInSessions {

    /** Why a session ended ({@code identity.sessions.revoke_reason}). */
    enum EndReason {
        REVOKED("revoked"),
        REVOKED_OTHERS("revoked_others"),
        SIGNED_OUT("signed_out");

        private final String code;

        EndReason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * An open session.
     *
     * @param apps names of the OAuth clients holding a live refresh token from it (e.g. "Northline Studio")
     */
    record Session(
            String id,
            @Nullable String device,
            @Nullable String city,
            @Nullable String ip,
            @Nullable String method,
            Instant signedInAt,
            @Nullable Instant lastSeenAt,
            List<String> apps) {}

    /** Whose a session is and whether it has ended. */
    record State(String userId, boolean ended) {}

    /**
     * Sessions of the user not ended and still alive: seen since {@code idleSince}, or holding a refresh token that
     * expires after {@code now}. Most recently seen first.
     */
    List<Session> open(String userId, Instant idleSince, Instant now);

    Optional<State> state(String sessionId);

    /** Sets "last seen" to {@code at} unless it was written after {@code notSince}. */
    void touch(String sessionId, Instant at, Instant notSince);

    /**
     * Ends these open sessions of the user and deletes the authorizations issued from them (refresh tokens stop working
     * at once). Returns the ids that were open.
     */
    List<String> end(String userId, Collection<String> sessionIds, EndReason reason, Instant at);

    /**
     * Ends every open session of the user except {@code keep}, and deletes every authorization of the user not issued
     * from one of {@code keep} (including authorizations older than S-19, which aren't linked to a session). Returns
     * the ids of the sessions ended.
     */
    List<String> endAllExcept(String userId, Collection<String> keep, EndReason reason, Instant at);

    /**
     * Records that an authorization was issued from a session (idempotent), and whether its refresh token still works
     * (a revoked or missing one no longer keeps the session listed as active).
     */
    void link(String authorizationId, String sessionId, boolean refreshable);
}
