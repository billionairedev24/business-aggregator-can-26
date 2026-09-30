package ca.northline.auth.partners;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2ErrorAuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/** Token endpoint errors: Spring's RFC 6749 answer, except a partner over its limit gets {@code 429} (S-30). */
public final class PartnerTokenErrors implements AuthenticationFailureHandler {

    private final AuthenticationFailureHandler standard = new OAuth2ErrorAuthenticationFailureHandler();

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException, ServletException {
        if (!(exception instanceof PartnerTokenProvider.RateLimited limited)) {
            standard.onAuthenticationFailure(request, response, exception);
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(limited.retryAfterSeconds()));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter()
                .write("{\"error\":\"rate_limited\",\"error_description\":\"Too many token requests. Wait %d s and"
                                .formatted(limited.retryAfterSeconds())
                        + " try again.\"}");
    }
}
