package ca.northline.auth.web;

import ca.northline.auth.application.AuthProperties;
import ca.northline.auth.application.BackupCodeService;
import ca.northline.auth.application.FlowRejected;
import ca.northline.auth.application.SessionAuthentication;
import ca.northline.auth.application.SessionService;
import ca.northline.auth.application.UserAccounts;
import ca.northline.auth.application.UserClaimsService;
import ca.northline.auth.domain.Factor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The auth server's own session:
 *
 * <pre>
 * GET  /api/auth/session        → 200 {user, acr} | 401
 * POST /api/auth/sign-out       → 204 (ends the auth session so "Not you?" can't silently sign back in)
 * POST /api/auth/backup-codes   → {codes:[10]} — replaces the previous set; needs a second-factor session
 * </pre>
 */
@RestController
@RequestMapping(path = "/api/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
class SessionController {

    private final UserAccounts accounts;
    private final BackupCodeService backupCodes;
    private final SessionSignIn sessions;
    private final SessionService sessionService;
    private final AuthProperties props;

    @GetMapping("/session")
    ResponseEntity<AuthResponses.Session> session(
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication) {
        if (!(authentication instanceof UsernamePasswordAuthenticationToken user)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return accounts.findById(user.getName())
                .map(a -> ResponseEntity.ok(
                        AuthResponses.Session.of(a, UserClaimsService.factorsOf(user), props.platformZone())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    /** Also ends the session (sign-in) itself: it leaves the Security tab's list and its refresh tokens stop (S-19). */
    @PostMapping("/sign-out")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void signOut(
            HttpServletRequest request,
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication) {
        if (authentication instanceof UsernamePasswordAuthenticationToken user) {
            SessionAuthentication.sessionIdOf(user).ifPresent(id -> sessionService.signedOut(user.getName(), id));
        }
        sessions.signOut(request);
    }

    @PostMapping("/backup-codes")
    AuthResponses.BackupCodes regenerate(
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication) {
        if (!(authentication instanceof UsernamePasswordAuthenticationToken user)
                || !Factor.isMfa(UserClaimsService.factorsOf(user))) {
            throw new FlowRejected(FlowRejected.Reason.UNAUTHENTICATED, "Sign in with a second factor first.");
        }
        return new AuthResponses.BackupCodes(backupCodes.regenerate(user.getName()));
    }
}
