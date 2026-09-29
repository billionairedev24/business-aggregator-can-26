package ca.northline.auth.web;

import ca.northline.auth.application.SignInLog;
import jakarta.servlet.http.HttpServletRequest;

/** Request → {@link SignInLog.Client} (remote address is already proxy-resolved by Tomcat's RemoteIpValve). */
final class Clients {

    private Clients() {}

    static SignInLog.Client of(HttpServletRequest request) {
        return new SignInLog.Client(request.getRemoteAddr(), request.getHeader("User-Agent"));
    }
}
