package ca.northline.auth.web;

import ca.northline.auth.application.RequestOrigin;
import ca.northline.auth.application.SignInLog;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Request → {@link SignInLog.Client}: the address and city are the client's, as resolved by {@code TrustedProxyFilter}
 * from trusted proxies only.
 */
final class Clients {

    private Clients() {}

    static SignInLog.Client of(HttpServletRequest request) {
        return new SignInLog.Client(
                request.getRemoteAddr(),
                request.getHeader("User-Agent"),
                request.getAttribute(RequestOrigin.CLIENT_CITY_ATTRIBUTE) instanceof String city ? city : null);
    }
}
