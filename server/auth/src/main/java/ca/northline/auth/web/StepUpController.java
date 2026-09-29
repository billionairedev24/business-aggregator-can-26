package ca.northline.auth.web;

import ca.northline.auth.application.FlowRejected;
import ca.northline.auth.application.StepUpService;
import ca.northline.auth.application.UserClaimsService;
import ca.northline.auth.domain.Factor;
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
 * Step-up for payouts and bank account changes (Finance workstream). Needs the auth session of someone signed in with a
 * second factor; the factor used here must be theirs.
 *
 * <pre>
 * POST /api/auth/step-up/passkey/options  → PublicKeyCredentialRequestOptions (JSON)
 * POST /api/auth/step-up/passkey  {credential}  → {proof, expiresAt}
 * POST /api/auth/step-up/totp     {code}        → {proof, expiresAt}
 * </pre>
 *
 * The Studio sends {@code proof} to the api as {@code X-Step-Up} with the money-moving request.
 */
@RestController
@RequestMapping(path = "/api/auth/step-up", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
class StepUpController {

    private final StepUpService stepUp;

    record StepUpProof(String proof, Instant expiresAt) {}

    @PostMapping("/passkey/options")
    String passkeyOptions(
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication) {
        return stepUp.passkeyOptions(signedIn(authentication));
    }

    @PostMapping("/passkey")
    StepUpProof passkey(
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication,
            @Valid @RequestBody AuthRequests.Passkey body) {
        var proof = stepUp.withPasskey(
                signedIn(authentication),
                Objects.requireNonNull(body.credential()).toString());
        return new StepUpProof(proof.token(), proof.expiresAt());
    }

    @PostMapping("/totp")
    StepUpProof totp(
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication,
            @Valid @RequestBody AuthRequests.Code body) {
        var proof = stepUp.withTotp(signedIn(authentication), Objects.requireNonNull(body.code()));
        return new StepUpProof(proof.token(), proof.expiresAt());
    }

    private static String signedIn(@Nullable Authentication authentication) {
        if (!(authentication instanceof UsernamePasswordAuthenticationToken user)
                || !Factor.isMfa(UserClaimsService.factorsOf(user))) {
            throw new FlowRejected(FlowRejected.Reason.UNAUTHENTICATED, "Sign in with a second factor first.");
        }
        return user.getName();
    }
}
