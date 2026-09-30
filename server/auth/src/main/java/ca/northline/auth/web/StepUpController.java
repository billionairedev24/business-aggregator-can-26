package ca.northline.auth.web;

import ca.northline.auth.application.FlowRejected;
import ca.northline.auth.application.StepUpProofs;
import ca.northline.auth.application.StepUpService;
import ca.northline.auth.application.UserClaimsService;
import ca.northline.auth.domain.Factor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Step-up for payouts and bank account changes (Finance workstream). Needs the auth session of someone signed in with a
 * second factor; the factor used here must be theirs.
 *
 * <pre>
 * POST /api/auth/step-up/passkey/options  → PublicKeyCredentialRequestOptions (JSON)
 * POST /api/auth/step-up/passkey  {credential}  → {proof, expiresAt}
 * POST /api/auth/step-up/totp     {code}        → {proof, expiresAt}
 * POST /api/auth/step-up/enrol/passkey/options  → PublicKeyCredentialCreationOptions (S-51)
 * POST /api/auth/step-up/enrol/passkey  {credential, label}  → {proof, expiresAt} (S-51)
 * </pre>
 *
 * <p>S-51 (consumer payments): a session signed in with a phone code only may step up too — the passkey or
 * authenticator code checked here is the second factor. An account with neither enrols a passkey within
 * {@link #ENROL_WINDOW} of its phone-code sign-in (the same assurance as registering with a passkey) and gets the
 * proof with it.
 *
 * The Studio sends {@code proof} to the api as {@code X-Step-Up} with the money-moving request. A successful step-up
 * also renews the session's second factor, which Settings › Security changes require to be recent (S-19).
 */
@RestController
@RequestMapping(path = "/api/auth/step-up", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
class StepUpController {

    private final StepUpService stepUp;
    private final SessionSignIn sessions;
    private final Clock clock;

    record StepUpProof(String proof, Instant expiresAt) {}

    @PostMapping("/passkey/options")
    String passkeyOptions(
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication) {
        return stepUp.passkeyOptions(signedIn(authentication));
    }

    @PostMapping("/passkey")
    StepUpProof passkey(
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication,
            @Valid @RequestBody AuthRequests.Passkey body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var proof = stepUp.withPasskey(
                signedIn(authentication),
                Objects.requireNonNull(body.credential()).toString());
        return confirmed(Objects.requireNonNull(authentication), Factor.PASSKEY, proof, request, response);
    }

    @PostMapping("/totp")
    StepUpProof totp(
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication,
            @Valid @RequestBody AuthRequests.Code body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var proof = stepUp.withTotp(signedIn(authentication), Objects.requireNonNull(body.code()));
        return confirmed(Objects.requireNonNull(authentication), Factor.TOTP, proof, request, response);
    }

    /** The session's factor time moves to now too: Settings › Security changes need a recent one (S-19). */
    private StepUpProof confirmed(
            Authentication authentication,
            Factor factor,
            StepUpProofs.Proof proof,
            HttpServletRequest request,
            HttpServletResponse response) {
        sessions.refreshFactor(authentication, factor, request, response);
        return new StepUpProof(proof.token(), proof.expiresAt());
    }

    @PostMapping("/enrol/passkey/options")
    String enrolOptions(
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication) {
        return stepUp.enrolOptions(recentlySignedIn(authentication));
    }

    @PostMapping("/enrol/passkey")
    StepUpProof enrol(
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication,
            @Valid @RequestBody AuthRequests.Passkey body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var proof = stepUp.enrolPasskey(
                recentlySignedIn(authentication),
                Objects.requireNonNull(body.credential()).toString(),
                Objects.requireNonNullElse(body.label(), "Passkey"));
        return confirmed(Objects.requireNonNull(authentication), Factor.PASSKEY, proof, request, response);
    }

    /** Any signed-in session (a phone-code one included, S-51): the factor verified by the step-up is the second. */
    private static String signedIn(@Nullable Authentication authentication) {
        if (!(authentication instanceof UsernamePasswordAuthenticationToken user)
                || UserClaimsService.factorsOf(user).isEmpty()) {
            throw new FlowRejected(FlowRejected.Reason.UNAUTHENTICATED, "Sign in first.");
        }
        return user.getName();
    }

    static final Duration ENROL_WINDOW = Duration.ofMinutes(15);

    /** Enrolling needs a sign-in (any factor) from the last {@link #ENROL_WINDOW}. */
    private String recentlySignedIn(@Nullable Authentication authentication) {
        var userId = signedIn(authentication);
        var recent = Objects.requireNonNull(authentication).getAuthorities().stream()
                .filter(a -> a instanceof FactorGrantedAuthority)
                .map(a -> ((FactorGrantedAuthority) a).getIssuedAt())
                .anyMatch(at -> at != null && at.isAfter(clock.instant().minus(ENROL_WINDOW)));
        if (!recent) {
            throw new FlowRejected(FlowRejected.Reason.UNAUTHENTICATED, "Sign in again to add a passkey.");
        }
        return userId;
    }
}
