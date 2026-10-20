package ca.northline.auth.web;

import ca.northline.auth.application.AuthProperties;
import ca.northline.auth.application.EnrollmentService;
import ca.northline.auth.application.FlowRejected;
import ca.northline.auth.application.UserAccounts;
import ca.northline.auth.application.UserClaimsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-154: the first second factor of a signed-in account that has none (right after its email is confirmed, or after a
 * password sign-in of an older account). Needs the auth session; 409 {@code already_enrolled} when the account has a
 * passkey or an authenticator.
 *
 * <pre>
 * POST /api/auth/enroll/totp              → {secret, otpauthUri, qrCode}
 * POST /api/auth/enroll/totp/verify       {code}               → {session (acr=mfa), backupCodes:[10]}
 * POST /api/auth/enroll/passkey/options   → PublicKeyCredentialCreationOptions (JSON)
 * POST /api/auth/enroll/passkey           {credential, label?} → {session (acr=mfa), backupCodes:[10]}
 * </pre>
 *
 * The session id changes when the factor is added (it just gained privileges).
 */
@RestController
@RequestMapping(path = "/api/auth/enroll", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
class EnrollmentController {

    private final EnrollmentService enrollment;
    private final UserAccounts accounts;
    private final SessionSignIn sessions;
    private final AppAuthorizationResume apps;
    private final AuthProperties props;

    @PostMapping("/totp")
    AuthResponses.TotpSetup totp(
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication) {
        var setup = enrollment.totpSetup(user(authentication).getName());
        return new AuthResponses.TotpSetup(setup.secret(), setup.otpauthUri(), setup.qrCode());
    }

    @PostMapping("/totp/verify")
    AuthResponses.Enrolled totpVerify(
            @Valid @RequestBody AuthRequests.Code body,
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        var user = user(authentication);
        var enrolled = enrollment.confirmTotp(user.getName(), Objects.requireNonNull(body.code()));
        return done(user, enrolled, request, response);
    }

    @PostMapping("/passkey/options")
    String passkeyOptions(
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication) {
        return enrollment.passkeyOptions(user(authentication).getName());
    }

    @PostMapping("/passkey")
    AuthResponses.Enrolled passkey(
            @Valid @RequestBody AuthRequests.Passkey body,
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        var user = user(authentication);
        var enrolled = enrollment.confirmPasskey(
                user.getName(),
                Objects.requireNonNull(body.credential()).toString(),
                Objects.requireNonNullElse(body.label(), "Passkey"));
        return done(user, enrolled, request, response);
    }

    private AuthResponses.Enrolled done(
            Authentication user,
            EnrollmentService.Enrolled enrolled,
            HttpServletRequest request,
            HttpServletResponse response) {
        sessions.refreshFactor(user, enrolled.factor(), request, response);
        var account = accounts.findById(user.getName()).orElseThrow();
        var factors = UserClaimsService.factorsOf(user);
        factors.add(enrolled.factor());
        var session =
                AuthResponses.Session.of(account, factors, props.platformZone()).continuingTo(apps.resume(request));
        return new AuthResponses.Enrolled(session, enrolled.backupCodes());
    }

    private static Authentication user(@Nullable Authentication authentication) {
        if (!(authentication instanceof UsernamePasswordAuthenticationToken user)) {
            throw new FlowRejected(FlowRejected.Reason.UNAUTHENTICATED, "Sign in first.");
        }
        return user;
    }
}
