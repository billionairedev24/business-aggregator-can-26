package ca.northline.auth.application;

import ca.northline.auth.domain.Factor;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: "every sign-in is logged". Successes go to {@code identity.sessions} (the device list) and
 * {@code developer.audit_log}; failures to the audit log only.
 */
public interface SignInLog {

    void succeeded(String userId, String method, boolean mfa, Client client);

    void failed(@Nullable String userId, Factor factor, String reason, Client client);

    /** A rate limit locked (S-9): {@code auth.rate_limited} in the audit log, whether or not the account exists. */
    void lockedOut(@Nullable String userId, String action, List<String> scopes, long seconds, Client client);

    /** Where the request came from. */
    record Client(@Nullable String ip, @Nullable String userAgent) {}
}
