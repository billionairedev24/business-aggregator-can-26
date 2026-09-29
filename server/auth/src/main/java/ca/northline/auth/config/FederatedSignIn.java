package ca.northline.auth.config;

import ca.northline.auth.application.AuthProperties;
import ca.northline.auth.application.SignInService;
import ca.northline.auth.application.UserAccounts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Google / Apple ("or continue with"). They only vouch for name and email, which is not a business second factor, so
 * the federated login never becomes the session: a known email continues at the Studio's factor step, an unknown one
 * opens "Create account" pre-filled. Client ids are placeholders until the Google/Apple apps exist (DECISIONS.md).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class FederatedSignIn implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private final UserAccounts accounts;
    private final SignInService signIn;
    private final AuthProperties props;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication auth)
            throws IOException {
        // Drop the federated authentication: a second factor is still required.
        var empty = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.clearContext();
        contexts.saveContext(empty, request, response);

        var user = auth.getPrincipal() instanceof OAuth2User u ? u : null;
        var email = attribute(user, "email");
        String target;
        if (email != null && accounts.findByEmail(email).isPresent()) {
            signIn.start(email);
            target = UriComponentsBuilder.fromUriString(props.loginPage())
                    .queryParam("step", "factor")
                    .queryParam("identifier", email)
                    .encode()
                    .toUriString();
        } else {
            var register = props.loginPage().replace("/sign-in", "/register");
            target = UriComponentsBuilder.fromUriString(register)
                    .queryParam("firstName", Objects.requireNonNullElse(attribute(user, "given_name"), ""))
                    .queryParam("lastName", Objects.requireNonNullElse(attribute(user, "family_name"), ""))
                    .queryParam("email", Objects.requireNonNullElse(email, ""))
                    .encode()
                    .toUriString();
        }
        response.sendRedirect(target);
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        log.info("Federated sign-in failed: {}", exception.getMessage());
        response.sendRedirect(UriComponentsBuilder.fromUriString(props.loginPage())
                .queryParam("error", "federation")
                .toUriString());
    }

    private static @Nullable String attribute(@Nullable OAuth2User user, String name) {
        if (user == null) {
            return null;
        }
        if (user instanceof OidcUser oidc && oidc.getClaims().get(name) instanceof String s) {
            return s;
        }
        return user.getAttribute(name);
    }
}
