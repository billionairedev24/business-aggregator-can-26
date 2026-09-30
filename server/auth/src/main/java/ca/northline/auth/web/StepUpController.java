package ca.northline.auth.web;

import ca.northline.auth.application.FlowRejected;
import ca.northline.auth.application.StepUpProofs;
import ca.northline.auth.application.StepUpService;
import ca.northline.auth.domain.Factor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Instant;
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
 * Step-up for payouts and bank account changes (Finance workstream) and, since S-55, for consumer payments. Needs a
 * signed-in auth session; the factor used here must be theirs. A consumer signed in with a phone code alone (S-62)
 * steps up with their passkey or authenticator: the phone code was the first factor, this is the second.
 *
 * <pre>
 * POST /api/auth/step-up/passkey/options  → PublicKeyCredentialRequestOptions (JSON)
 * POST /api/auth/step-up/passkey  {credential}  → {proof, expiresAt}
 * POST /api/auth/step-up/totp     {code}        → {proof, expiresAt}
 * </pre>
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

    private static String signedIn(@Nullable Authentication authentication) {
        if (!(authentication instanceof UsernamePasswordAuthenticationToken user) || !user.isAuthenticated()) {
            throw new FlowRejected(FlowRejected.Reason.UNAUTHENTICATED, "Sign in first.");
        }
        return user.getName();
    }
}
