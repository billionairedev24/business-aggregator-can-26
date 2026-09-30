package ca.northline.auth.application;

import ca.northline.auth.domain.Factor;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: "every sign-in is logged". Successes go to {@code identity.sessions} (the device list) and
 * {@code developer.audit_log}; failures to the audit log only.
 */
public interface SignInLog {

    /** Logs the sign-in and returns its id: the session the Studio lists and can revoke (S-19). */
    String succeeded(String userId, String method, boolean mfa, Client client);

    void failed(@Nullable String userId, Factor factor, String reason, Client client);

    /** A rate limit locked (S-9): {@code auth.rate_limited} in the audit log, whether or not the account exists. */
    void lockedOut(@Nullable String userId, String action, List<String> scopes, long seconds, Client client);

    /**
     * Where the request came from.
     *
     * @param city approximate city from the ingress / CDN geo header ({@code northline.auth.client-city-header}), only
     *     believed from a trusted proxy
     */
    record Client(
            @Nullable String ip,
            @Nullable String userAgent,
            @Nullable String city) {

        public Client(@Nullable String ip, @Nullable String userAgent) {
            this(ip, userAgent, null);
        }
    }
}
