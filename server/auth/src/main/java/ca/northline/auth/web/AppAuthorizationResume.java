package ca.northline.auth.web;

import ca.northline.auth.clients.RegisteredClients;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;

/**
 * S-29: a mobile app opens {@code /oauth2/authorize} in the system browser; without a session the auth server sends that
 * browser to the sign-in page ({@code northline.auth.login-page}) and remembers the request in the auth session
 * (Spring Security's request cache). Once the JSON sign-in or registration succeeds, the page must go back to that
 * request so the app gets its code — not on to the Studio's BFF. Only a public client's authorization request on this
 * server is handed back (the Studio's own BFF flow is unchanged), and only once.
 */
@Component
@RequiredArgsConstructor
class AppAuthorizationResume {

    /** Spring Security's {@code HttpSessionRequestCache} attribute. */
    static final String SAVED_REQUEST = "SPRING_SECURITY_SAVED_REQUEST";

    private final RegisteredClientRepository clients;

    @Nullable
    String resume(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(SAVED_REQUEST) instanceof SavedRequest saved)) {
            return null;
        }
        var url = saved.getRedirectUrl();
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException _) {
            return null;
        }
        if (!"/oauth2/authorize".equals(uri.getPath())) {
            return null;
        }
        var clientId = saved.getParameterValues(OAuth2ParameterNames.CLIENT_ID);
        var client = clientId == null || clientId.length != 1 ? null : clients.findByClientId(clientId[0]);
        if (client == null || !RegisteredClients.isPublic(client)) {
            return null;
        }
        session.removeAttribute(SAVED_REQUEST);
        return url;
    }
}
