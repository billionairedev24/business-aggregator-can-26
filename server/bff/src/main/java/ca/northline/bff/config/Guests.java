package ca.northline.bff.config;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.Base64;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * S-45 consumer-bff: the guest id of a browsing session — 128 random bits, kept in the BFF session (so it survives the
 * sign-in, whose new session id keeps the attributes) and relayed to the api as {@value #HEADER}, where it keys what
 * a guest owns before signing in (the cart, docs/CONSUMER_WEB_PLAN.md § Cart). It identifies a browser, never a
 * person: the api must not treat it as authentication.
 */
@Component
public class Guests {

    public static final String HEADER = "X-Northline-Guest";
    static final String SESSION_KEY = "nl.bff.guest-id";

    private final SecureRandom random = new SecureRandom();

    /** The session's guest id, created (with the session) when missing. */
    public String ensure(HttpServletRequest request) {
        var session = request.getSession(true);
        if (session.getAttribute(SESSION_KEY) instanceof String id) {
            return id;
        }
        var bytes = new byte[16];
        random.nextBytes(bytes);
        var id = "g_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(SESSION_KEY, id);
        return id;
    }

    /** The guest id if this request has a session with one; never creates a session (bots, the SSR server). */
    public static @Nullable String existing(HttpServletRequest request) {
        var session = request.getSession(false);
        return session != null && session.getAttribute(SESSION_KEY) instanceof String id ? id : null;
    }
}
