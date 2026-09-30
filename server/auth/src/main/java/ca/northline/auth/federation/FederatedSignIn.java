package ca.northline.auth.federation;

import ca.northline.auth.application.FederatedProfile;
import ca.northline.auth.application.FederatedSignInService;
import ca.northline.auth.application.FederatedSignInService.ContinueSignIn;
import ca.northline.auth.application.FederatedSignInService.CreateAccount;
import ca.northline.auth.application.FlowRejected;
import ca.northline.auth.application.LoginPages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Back from Google / Apple (S-18). The federated login never becomes the session (a second factor is still required):
 * the profile goes to {@link FederatedSignInService}, and the browser continues in the Studio (or, S-62, on the
 * consumer site when it started there — {@link LoginPages#forFederationCallback}) — the factor step
 * ({@code /sign-in?step=factor&identifier=…[&link=google]}) or "Create account" pre-filled
 * ({@code /register?firstName=…&lastName=…&email=…&provider=apple[&relay=1]}). Failures land on
 * {@code /sign-in?error=federation_cancelled | federation_unavailable | federation | rate_limited}.
 */
@Slf4j
@RequiredArgsConstructor
final class FederatedSignIn implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    /** Apple sends the name only once, as a JSON form field next to the code (never in the ID token). */
    static final String APPLE_USER_PARAMETER = "user";

    private final FederatedSignInService federation;
    private final LoginPages pages;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication auth)
            throws IOException {
        // Drop the federated authentication: a second factor is still required.
        var empty = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.clearContext();
        contexts.saveContext(empty, request, response);

        var profile = profile(auth, request);
        var page = pages.forFederationCallback(request); // S-62: the Studio's or the consumer site's
        if (profile == null) {
            response.sendRedirect(error(page, "federation"));
            return;
        }
        FederatedSignInService.Next next;
        try {
            next = federation.signedIn(profile);
        } catch (FlowRejected e) {
            response.sendRedirect(error(page, e.getReason().code()));
            return;
        }
        var target = switch (next) {
            case ContinueSignIn(var identifier, var linking) -> {
                var uri = UriComponentsBuilder.fromUriString(page)
                        .queryParam("step", "factor")
                        .queryParam("identifier", identifier);
                yield (linking ? uri.queryParam("link", profile.provider()) : uri)
                        .encode()
                        .toUriString();
            }
            case CreateAccount(var first, var last, var email, var relay) -> {
                var uri = UriComponentsBuilder.fromUriString(LoginPages.registerPage(page))
                        .queryParam("firstName", first)
                        .queryParam("lastName", last)
                        .queryParam("email", email == null ? "" : email)
                        .queryParam("provider", profile.provider());
                yield (relay ? uri.queryParam("relay", "1") : uri).encode().toUriString();
            }
        };
        response.sendRedirect(target);
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        var code = exception instanceof OAuth2AuthenticationException oauth
                ? oauth.getError().getErrorCode()
                : "";
        log.info("Federated sign-in failed ({}): {}", code, exception.getMessage());
        response.sendRedirect(error(
                pages.forFederationCallback(request),
                switch (code) {
                    // The person pressed Cancel (Google: access_denied; Apple: user_cancelled_authorize).
                    case "access_denied", "user_cancelled_authorize" -> "federation_cancelled";
                    // Provider down or refusing us (e.g. a wrong client secret): nothing the person can fix.
                    case "invalid_token_response",
                            "invalid_user_info_response",
                            "server_error",
                            "temporarily_unavailable",
                            "invalid_client",
                            "unauthorized_client" -> "federation_unavailable";
                    default -> "federation";
                }));
    }

    private static String error(String page, String code) {
        return UriComponentsBuilder.fromUriString(page)
                .queryParam("error", code)
                .toUriString();
    }

    private @Nullable FederatedProfile profile(Authentication auth, HttpServletRequest request) {
        if (!(auth instanceof OAuth2AuthenticationToken token) || !(auth.getPrincipal() instanceof OAuth2User user)) {
            return null;
        }
        var provider = token.getAuthorizedClientRegistrationId();
        var subject = string(user, "sub");
        if (subject == null) {
            return null;
        }
        var email = string(user, "email");
        String given = string(user, "given_name");
        String family = string(user, "family_name");
        if (FederationRegistrations.APPLE.equals(provider) && given == null) {
            var name = appleName(request.getParameter(APPLE_USER_PARAMETER));
            given = name.get("firstName");
            family = name.get("lastName");
        }
        return new FederatedProfile(
                provider,
                subject,
                email == null ? null : email.strip().toLowerCase(Locale.ROOT),
                // Apple only hands out addresses it verified (and says so, as a string or a boolean).
                bool(user, "email_verified") || (FederationRegistrations.APPLE.equals(provider) && email != null),
                bool(user, "is_private_email"),
                given,
                family);
    }

    private Map<String, @Nullable String> appleName(@Nullable String userJson) {
        if (userJson == null || userJson.isBlank() || userJson.length() > 2_000) {
            return Map.of();
        }
        try {
            JsonNode name = json.readTree(userJson).path("name");
            var first = name.path("firstName").asString("");
            var last = name.path("lastName").asString("");
            return Map.of("firstName", first.strip(), "lastName", last.strip());
        } catch (RuntimeException e) {
            log.info("Apple user parameter unreadable: {}", e.getMessage());
            return Map.of();
        }
    }

    private static @Nullable String string(OAuth2User user, String claim) {
        var value = user instanceof OidcUser oidc ? oidc.getClaims().get(claim) : user.getAttribute(claim);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static boolean bool(OAuth2User user, String claim) {
        var value = user instanceof OidcUser oidc ? oidc.getClaims().get(claim) : user.getAttribute(claim);
        return Boolean.TRUE.equals(value) || "true".equals(value);
    }
}
