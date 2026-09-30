package ca.northline.auth.application;

import org.jspecify.annotations.Nullable;

/** Outbound port: where the current request comes from (client IP after trusted proxies, auth session id). */
public interface RequestOrigin {

    /**
     * Request attribute holding the approximate city of the client, copied by {@code TrustedProxyFilter} from the
     * ingress / CDN geo header ({@code northline.auth.client-city-header}) of a trusted proxy.
     */
    String CLIENT_CITY_ATTRIBUTE = "ca.northline.auth.client-city";

    @Nullable
    String ip();

    /** The auth server's HTTP session of this browser (created when missing). */
    String sessionId();
}
