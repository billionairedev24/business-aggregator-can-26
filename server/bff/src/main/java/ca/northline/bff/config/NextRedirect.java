package ca.northline.bff.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * After the OAuth callback, returns the browser to where the Studio asked ({@code GET /bff/login?next=/…}). Only
 * same-origin paths are accepted, so this can't be used as an open redirect.
 */
@Component
public class NextRedirect implements AuthenticationSuccessHandler {

    public static final String SESSION_KEY = "nl.bff.next";

    /** {@code next} if it is a local path ({@code /…}, not {@code //…} or {@code /\…}), else {@code /}. */
    public static String safe(@Nullable String next) {
        if (next == null || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) {
            return "/";
        }
        return next.chars().anyMatch(c -> c < 0x20) ? "/" : next;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        var session = request.getSession(false);
        var next = session == null ? null : (String) session.getAttribute(SESSION_KEY);
        if (session != null) {
            session.removeAttribute(SESSION_KEY);
        }
        response.sendRedirect(safe(next));
    }
}
