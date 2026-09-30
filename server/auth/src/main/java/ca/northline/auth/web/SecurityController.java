package ca.northline.auth.web;

import ca.northline.auth.application.AccountSecurity;
import ca.northline.auth.application.Caller;
import ca.northline.auth.application.FlowRejected;
import ca.northline.auth.application.SecuritySettingsService;
import ca.northline.auth.application.SessionAuthentication;
import ca.northline.auth.application.SessionService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Studio Settings › Security (settings &amp; compliance workstream). Needs an auth session signed in with a second
 * factor, like {@code POST /api/auth/backup-codes}; otherwise 401 {@code unauthenticated} and the Studio asks the person
 * to confirm it's them.
 *
 * <pre>
 * GET    /api/auth/security[?current=sid]          passkeys, authenticator, backup codes left, recent sign-ins,
 *                                                  active sessions (current = this one, or the BFF's sid)
 * POST   /api/auth/security/passkeys/options       → PublicKeyCredentialCreationOptions (JSON)
 * POST   /api/auth/security/passkeys               {credential, label} → 201 {passkeys}
 * DELETE /api/auth/security/passkeys/{id}          → {passkeys}; 409 last_factor
 * POST   /api/auth/security/sessions/{id}/revoke   {current?} → {sessions}; 409 current_session, 404 not_found
 * POST   /api/auth/security/sessions/revoke-others {current?} → {revoked: n}
 * </pre>
 *
 * The three changes (S-19) need a second factor used in this session in the last 10 minutes — else 403
 * {@code step_up_required} (the Studio confirms with {@code /api/auth/step-up/*} and retries) — count against the S-9
 * {@code security-change} limits and are audit-logged.
 */
@RestController
@RequestMapping(path = "/api/auth/security", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
class SecurityController {

    private final SecuritySettingsService settings;
    private final SessionService sessions;

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
            List<SignInView> signIns,
            List<SessionView> sessions) {}

    record Passkeys(List<PasskeyView> passkeys) {}

    record SessionView(
            String id,
            @Nullable String device,
            @Nullable String city,
            @Nullable String ipApprox,
            @Nullable String method,
            Instant signedInAt,
            @Nullable Instant lastSeenAt,
            List<String> apps,
            boolean current) {
        static SessionView of(SessionService.ActiveSession s) {
            return new SessionView(
                    s.id(),
                    s.device(),
                    s.city(),
                    s.ipApprox(),
                    s.method(),
                    s.signedInAt(),
                    s.lastSeenAt(),
                    s.apps(),
                    s.current());
        }
    }

    record Sessions(List<SessionView> sessions) {}

    record Revoked(int revoked) {}

    /** The BFF session's {@code sid} (from {@code GET /bff/session}), so it counts as current too. */
    record CurrentSession(@Nullable String current) {}

    @GetMapping
    Overview overview(
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication,
            @RequestParam(required = false) @Nullable String current) {
        var caller = caller(authentication);
        var o = settings.overview(caller.userId());
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
                        .toList(),
                sessions.list(caller, current).stream().map(SessionView::of).toList());
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

    @DeleteMapping("/passkeys/{id}")
    Passkeys removePasskey(
            @PathVariable String id,
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication) {
        var list = settings.removePasskey(caller(authentication), id);
        return new Passkeys(list.stream().map(PasskeyView::of).toList());
    }

    @PostMapping("/sessions/{id}/revoke")
    Sessions revoke(
            @PathVariable String id,
            @RequestBody(required = false) @Nullable CurrentSession body,
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication) {
        var list = sessions.revoke(caller(authentication), id, body == null ? null : body.current());
        return new Sessions(list.stream().map(SessionView::of).toList());
    }

    @PostMapping("/sessions/revoke-others")
    Revoked revokeOthers(
            @RequestBody(required = false) @Nullable CurrentSession body,
            @CurrentSecurityContext(expression = "authentication") @Nullable Authentication authentication) {
        return new Revoked(sessions.revokeOthers(caller(authentication), body == null ? null : body.current()));
    }

    private static String mfaUser(@Nullable Authentication authentication) {
        return caller(authentication).userId();
    }

    private static Caller caller(@Nullable Authentication authentication) {
        if (!(authentication instanceof UsernamePasswordAuthenticationToken user)
                || !Factor.isMfa(UserClaimsService.factorsOf(user))) {
            throw new FlowRejected(FlowRejected.Reason.UNAUTHENTICATED, "Sign in with a second factor first.");
        }
        return SessionAuthentication.caller(user);
    }
}
