package ca.northline.auth.web;

import ca.northline.auth.application.AuthProperties;
import ca.northline.auth.application.EnrollmentService;
import ca.northline.auth.application.PhoneCodeSignInService;
import ca.northline.auth.application.SignInService;
import ca.northline.auth.domain.Factor;
import ca.northline.auth.domain.OtpChallenge.Channel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign in — the Studio's "Sign in" tab:
 *
 * <pre>
 * POST /api/auth/sign-in                   {identifier}  → {identifier, factors:[passkey, totp, backup_code]}
 * POST /api/auth/sign-in/passkey/options   → PublicKeyCredentialRequestOptions (JSON)
 * POST /api/auth/sign-in/passkey           {credential}  → session
 * POST /api/auth/sign-in/totp              {code}        → session
 * POST /api/auth/sign-in/backup-code       {code}        → session (the code is used up)
 * </pre>
 *
 * <p>S-154, email + password first (the Studio, the console, the consumer site):
 *
 * <pre>
 * POST /api/auth/sign-in/password      {email, password} → {step:"factor", factors:[the account's own]}
 *                                                          | {step:"enroll", session} (no second factor yet)
 *                                                          | {step:"verify-email", email, resendAfterSeconds}
 * </pre>
 *
 * then the factor endpoints above; the session holds the password and the factor ({@code amr: pwd + otp|hwk}). An
 * account with a password can't skip it: its authenticator or backup code counts only after it.
 *
 * <p>S-62, the consumer site: after {@code POST /api/auth/sign-in}, a code to the account's phone instead of a second
 * factor (a single-factor sign-in: no {@code acr=mfa}):
 *
 * <pre>
 * POST /api/auth/sign-in/code          {channel?: sms|voice} → {resendAfterSeconds, channel} (429 otp_throttled inside 45 s)
 * POST /api/auth/sign-in/code/verify   {code}                → session
 * </pre>
 */
@RestController
@RequestMapping(path = "/api/auth/sign-in", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
class SignInController {

    private final SignInService signIn;
    private final PhoneCodeSignInService phoneCodes;
    private final SessionSignIn sessions;
    private final AppAuthorizationResume apps;
    private final AuthProperties props;
    private final EnrollmentService enrollment;

    @PostMapping("/password")
    AuthResponses.PasswordStep password(
            @Valid @RequestBody AuthRequests.EmailPassword body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var outcome = signIn.startWithPassword(
                Objects.requireNonNull(body.email()), Objects.requireNonNull(body.password()), Clients.of(request));
        return switch (outcome) {
            case SignInService.PasswordOutcome.SecondFactor(var identifier, var factors) ->
                new AuthResponses.PasswordStep(
                        "factor", factors.stream().map(Factor::code).toList(), null, identifier, null);
            case SignInService.PasswordOutcome.Enrol(var done) ->
                new AuthResponses.PasswordStep("enroll", null, establish(done, request, response), null, null);
            case SignInService.PasswordOutcome.ConfirmEmail(var email, var wait) ->
                new AuthResponses.PasswordStep(AccountController.VERIFY_EMAIL, null, null, email, wait);
        };
    }

    @PostMapping
    AuthResponses.SignInStarted start(@Valid @RequestBody AuthRequests.Identifier body) {
        var attempt = signIn.start(Objects.requireNonNull(body.identifier()));
        return new AuthResponses.SignInStarted(
                attempt.identifier(),
                SignInService.OFFERED.stream().map(Factor::code).toList());
    }

    @PostMapping("/passkey/options")
    String passkeyOptions() {
        return signIn.passkeyOptions();
    }

    @PostMapping("/passkey")
    AuthResponses.Session passkey(
            @Valid @RequestBody AuthRequests.Passkey body, HttpServletRequest request, HttpServletResponse response) {
        var done = signIn.completeWithPasskey(
                Objects.requireNonNull(body.credential()).toString(), Clients.of(request));
        return establish(done, request, response);
    }

    @PostMapping("/totp")
    AuthResponses.Session totp(
            @Valid @RequestBody AuthRequests.Code body, HttpServletRequest request, HttpServletResponse response) {
        return establish(
                signIn.verifyTotp(Objects.requireNonNull(body.code()), Clients.of(request)), request, response);
    }

    @PostMapping("/backup-code")
    AuthResponses.Session backupCode(
            @Valid @RequestBody AuthRequests.BackupCode body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var done = signIn.verifyBackupCode(Objects.requireNonNull(body.code()), Clients.of(request));
        return establish(done, request, response);
    }

    @PostMapping("/code")
    AuthResponses.CodeSent code(@RequestBody(required = false) AuthRequests.@Nullable Resend body) {
        var voice = body != null
                && "voice"
                        .equals(Objects.requireNonNullElse(body.channel(), "sms")
                                .toLowerCase(Locale.ROOT));
        var sent = phoneCodes.send(voice ? Channel.VOICE : Channel.SMS);
        return new AuthResponses.CodeSent(
                sent.resendAfterSeconds(), sent.channel().name().toLowerCase(Locale.ROOT));
    }

    @PostMapping("/code/verify")
    AuthResponses.Session codeVerify(
            @Valid @RequestBody AuthRequests.Code body, HttpServletRequest request, HttpServletResponse response) {
        return establish(
                phoneCodes.verify(Objects.requireNonNull(body.code()), Clients.of(request)), request, response);
    }

    private AuthResponses.Session establish(
            SignInService.SignedIn done, HttpServletRequest request, HttpServletResponse response) {
        var factors = done.factors();
        sessions.signIn(done.account().id(), factors, done.sessionId(), request, response);
        return AuthResponses.Session.of(done.account(), factors, props.platformZone())
                .needingSecondFactor(!Factor.isMfa(factors)
                        && enrollment.needsSecondFactor(done.account().id()))
                .continuingTo(apps.resume(request));
    }
}
