package ca.northline.auth.web;

import ca.northline.auth.application.AccountSecurity;
import ca.northline.auth.application.FlowRejected;
import ca.northline.auth.application.SecuritySettingsService;
import ca.northline.auth.application.UserClaimsService;
import ca.northline.auth.domain.Factor;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Studio Settings › Security (settings &amp; compliance workstream). Needs an auth session signed in with a second
 * factor, like {@code POST /api/auth/backup-codes}; otherwise 401 {@code unauthenticated} and the Studio asks the person
 * to confirm it's them.
 *
 * <pre>
 * GET  /api/auth/security                     passkeys, authenticator, backup codes left, recent sign-ins
 * POST /api/auth/security/passkeys/options    → PublicKeyCredentialCreationOptions (JSON)
 * POST /api/auth/security/passkeys            {credential, label} → 201 {passkeys}
 * </pre>
 */
@RestController
@RequestMapping(path = "/api/auth/security", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
class SecurityController {

    private final SecuritySettingsService settings;

    record PasskeyView(
            String id,
            String label,
            @Nullable Instant createdAt,
            @Nullable Instant lastUsedAt) {
        static PasskeyView of(AccountSecurity.Passkey p) {
            return new PasskeyView(p.id(), p.label(), p.createdAt(), p.lastUsedAt());
        }
    }

    record SignInView(
            String id,
            @Nullable String device,
            @Nullable String city,
            @Nullable String method,
            Instant at,
            @Nullable Instant lastSeenAt) {}

    record Overview(
            @Nullable String email,
            @Nullable String mfaPrimary,
            List<PasskeyView> passkeys,
            boolean authenticator,
            @Nullable Instant authenticatorSince,
            int backupCodesRemaining,
            @Nullable Instant backupCodesIssuedAt,
            List<SignInView> signIns) {}

    record Passkeys(List<PasskeyView> passkeys) {}

    @GetMapping
    Overview overview(@CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication) {
        var o = settings.overview(mfaUser(authentication));
        return new Overview(
                o.account().email(),
                o.account().mfaPrimary(),
                o.passkeys().stream().map(PasskeyView::of).toList(),
                o.authenticatorSince() != null,
                o.authenticatorSince(),
                o.backupCodes().remaining(),
                o.backupCodes().issuedAt(),
                o.signIns().stream()
                        .map(s -> new SignInView(s.id(), s.device(), s.city(), s.method(), s.at(), s.lastSeenAt()))
                        .toList());
    }

    @PostMapping("/passkeys/options")
    String passkeyOptions(
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication) {
        return settings.passkeyOptions(mfaUser(authentication));
    }

    @PostMapping("/passkeys")
    @ResponseStatus(HttpStatus.CREATED)
    Passkeys addPasskey(
            @Valid @RequestBody AuthRequests.Passkey body,
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication) {
        var list = settings.addPasskey(
                mfaUser(authentication),
                Objects.requireNonNull(body.credential()).toString(),
                body.label());
        return new Passkeys(list.stream().map(PasskeyView::of).toList());
    }

    private static String mfaUser(@Nullable Authentication authentication) {
        if (!(authentication instanceof UsernamePasswordAuthenticationToken user)
                || !Factor.isMfa(UserClaimsService.factorsOf(user))) {
            throw new FlowRejected(FlowRejected.Reason.UNAUTHENTICATED, "Sign in with a second factor first.");
        }
        return user.getName();
    }
}
