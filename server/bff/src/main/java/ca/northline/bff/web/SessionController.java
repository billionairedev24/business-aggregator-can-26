package ca.northline.bff.web;

import ca.northline.bff.config.BffProperties;
import ca.northline.bff.config.NextRedirect;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/**
 * The Studio's session contract (IMPLEMENTATION_PLAN.md § Contracts):
 *
 * <pre>
 * GET  /bff/session          → 200 {user:{id, firstName, lastName, email, phone, initials, locale, memberSince}, acr, sid} | 401
 * POST /bff/logout           → 204 (Spring Security logout; see BffSecurityConfig)
 * GET  /bff/login?next=/path → 302 /oauth2/authorization/studio, back to {@code next} after the callback
 * </pre>
 */
@RestController
class SessionController {

    private final BffProperties props;

    SessionController(BffProperties props) {
        this.props = props;
    }

    /**
     * The signed-in user, from the ID token northline-auth issued. {@code sid} = the session (sign-in) at northline-auth
     * this BFF session belongs to (S-19): Settings › Security marks it as the current one.
     */
    record SessionResponse(
            User user, @Nullable String acr, @Nullable String sid) {}

    record User(
            String id,
            String firstName,
            String lastName,
            @Nullable String email,
            @Nullable String phone,
            String initials,
            String locale,
            @Nullable String memberSince) {}

    @GetMapping(path = "/bff/session", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SessionResponse> session(@AuthenticationPrincipal @Nullable OidcUser user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        var first = Objects.requireNonNullElse(user.getGivenName(), "");
        var last = Objects.requireNonNullElse(user.getFamilyName(), "");
        var initials = (initial(first) + initial(last)).toUpperCase(Locale.ROOT);
        var body = new SessionResponse(
                new User(
                        user.getName(),
                        first,
                        last,
                        user.getEmail(),
                        user.getPhoneNumber(),
                        initials.isEmpty() ? "NL" : initials,
                        Objects.requireNonNullElse(user.getLocale(), "en-CA"),
                        user.getClaimAsString("member_since")),
                user.getClaimAsString("acr"),
                user.getClaimAsString("sid"));
        return ResponseEntity.ok(body);
    }

    /**
     * Hand-off after the Studio's own sign-in against northline-auth: the auth session already exists, so the
     * authorization request comes straight back with a code and the user lands on {@code next} without seeing a page.
     */
    @GetMapping("/bff/login")
    RedirectView login(@RequestParam(required = false) @Nullable String next, HttpServletRequest request)
            throws IOException {
        request.getSession(true).setAttribute(NextRedirect.SESSION_KEY, NextRedirect.safe(next));
        var redirect = new RedirectView("/oauth2/authorization/" + props.registrationId(), true);
        redirect.setStatusCode(HttpStatus.FOUND);
        return redirect;
    }

    private static String initial(String s) {
        return s.isEmpty() ? "" : s.substring(0, 1);
    }
}
