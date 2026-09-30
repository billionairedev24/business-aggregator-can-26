package ca.northline.bff.web;

import ca.northline.bff.config.BffProperties;
import ca.northline.bff.config.Guests;
import ca.northline.bff.config.NextRedirect;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
 *
 * <p>S-45 consumer-bff ({@code northline.bff.guests}): {@code GET /bff/session} answers 200 for guests too, and every
 * answer carries the session's {@code guestId} and, when the edge supplies it, the visitor's {@code location.city}
 * (docs/CONSUMER_WEB_PLAN.md § Session):
 *
 * <pre>
 * GET /bff/session → 200 {user: null | {…}, acr, sid, guestId, location: {city} | absent}
 * </pre>
 */
@RestController
class SessionController {

    private final BffProperties props;
    private final Guests guests;

    SessionController(BffProperties props, Guests guests) {
        this.props = props;
        this.guests = guests;
    }

    /**
     * The signed-in user, from the ID token northline-auth issued. {@code sid} = the session (sign-in) at northline-auth
     * this BFF session belongs to (S-19): Settings › Security marks it as the current one.
     */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record SessionResponse(
            @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable
            User user,

            @Nullable String acr,
            @Nullable String sid,
            @Nullable String guestId,
            @Nullable Location location) {}

    /** The visitor's city from the CDN / ingress header ({@code northline.bff.client-city-header}). */
    record Location(String city) {}

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
    ResponseEntity<SessionResponse> session(
            @AuthenticationPrincipal @Nullable OidcUser user, HttpServletRequest request) {
        var guestId = props.guests() ? guests.ensure(request) : null;
        var location = props.guests() ? location(request) : null;
        if (user == null) {
            return props.guests()
                    ? ResponseEntity.ok(new SessionResponse(null, null, null, guestId, location))
                    : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
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
                user.getClaimAsString("sid"),
                guestId,
                location);
        return ResponseEntity.ok(body);
    }

    /** The city the edge put in the configured header (decoded, at most 60 characters), or none. */
    private @Nullable Location location(HttpServletRequest request) {
        if (props.clientCityHeader().isBlank()) {
            return null;
        }
        var raw = request.getHeader(props.clientCityHeader());
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String city;
        try {
            city = URLDecoder.decode(raw, StandardCharsets.UTF_8).strip();
        } catch (IllegalArgumentException e) {
            city = raw.strip();
        }
        city = city.codePoints()
                .filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        return city.isEmpty() ? null : new Location(city.length() > 60 ? city.substring(0, 60) : city);
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
