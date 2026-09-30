package ca.northline.auth.application;

import org.jspecify.annotations.Nullable;

/** Outbound port: where the current request comes from (client IP after trusted proxies, auth session id). */
public interface RequestOrigin {

    @Nullable
    String ip();

    /** The auth server's HTTP session of this browser (created when missing). */
    String sessionId();
}
