package ca.northline.auth.web;

import ca.northline.auth.application.AuthProperties;
import ca.northline.auth.application.RegistrationService;
import ca.northline.auth.domain.Factor;
import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PendingRegistration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Create account — the Studio's "Create account" tab drives these in order:
 *
 * <pre>
 * POST /api/auth/register                   form → code sent by SMS          → {step:"otp", phone, resendAfterSeconds}
 * POST /api/auth/register/resend            {channel: sms|voice}             → 429 otp_throttled inside 45 s
 * POST /api/auth/register/verify            {code}                           → {step:"mfa"}
 * POST /api/auth/register/passkey/options   → PublicKeyCredentialCreationOptions (JSON)
 * POST /api/auth/register/passkey           {credential}                     → 201 session (account created)
 * POST /api/auth/register/totp              → {secret, otpauthUri, qrCode}
 * POST /api/auth/register/totp/verify       {code}                           → 201 session (account created)
 * POST /api/auth/register/complete                                           → 201 session (S-62: no second factor)
 * </pre>
 */
@RestController
@RequestMapping(path = "/api/auth/register", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
class RegistrationController {

    private final RegistrationService registration;
    private final SessionSignIn sessions;
    private final AppAuthorizationResume apps;
    private final AuthProperties props;
    private final Clock clock;

    @PostMapping
    AuthResponses.RegistrationStep start(@Valid @RequestBody AuthRequests.Register body) {
        var pending = registration.start(new RegistrationService.Start(
                Objects.requireNonNull(body.firstName()),
                Objects.requireNonNull(body.lastName()),
                Objects.requireNonNull(body.phone()),
                Objects.requireNonNull(body.email())));
        return step("otp", pending);
    }

    @PostMapping("/resend")
    AuthResponses.RegistrationStep resend(@RequestBody AuthRequests.Resend body) {
        var channel =
                "voice".equals(Objects.requireNonNullElse(body.channel(), "sms").toLowerCase(Locale.ROOT))
                        ? Channel.VOICE
                        : Channel.SMS;
        return step("otp", registration.resend(channel));
    }

    @PostMapping("/verify")
    AuthResponses.RegistrationStep verify(@Valid @RequestBody AuthRequests.Code body) {
        return step("mfa", registration.verifyPhone(Objects.requireNonNull(body.code())));
    }

    @PostMapping("/passkey/options")
    String passkeyOptions() {
        return registration.passkeyOptions();
    }

    @PostMapping("/passkey")
    @ResponseStatus(HttpStatus.CREATED)
    AuthResponses.Session passkey(
            @Valid @RequestBody AuthRequests.Passkey body, HttpServletRequest request, HttpServletResponse response) {
        var label = Objects.requireNonNullElse(body.label(), "Passkey");
        var created = registration.completeWithPasskey(
                Objects.requireNonNull(body.credential()).toString(), label, Clients.of(request));
        return signIn(created, request, response);
    }

    @PostMapping("/totp")
    AuthResponses.TotpSetup totp() {
        var setup = registration.totpSetup();
        return new AuthResponses.TotpSetup(setup.secret(), setup.otpauthUri(), setup.qrCode());
    }

    @PostMapping("/totp/verify")
    @ResponseStatus(HttpStatus.CREATED)
    AuthResponses.Session totpVerify(
            @Valid @RequestBody AuthRequests.Code body, HttpServletRequest request, HttpServletResponse response) {
        var created = registration.completeWithTotp(Objects.requireNonNull(body.code()), Clients.of(request));
        return signIn(created, request, response);
    }

    /** S-62 (consumer, design 06 "SMS code · Backup only"): the account without a second factor. */
    @PostMapping("/complete")
    @ResponseStatus(HttpStatus.CREATED)
    AuthResponses.Session complete(HttpServletRequest request, HttpServletResponse response) {
        return signIn(registration.completeWithoutSecondFactor(Clients.of(request)), request, response);
    }

    private AuthResponses.Session signIn(
            RegistrationService.Created created, HttpServletRequest request, HttpServletResponse response) {
        var factors = created.secondFactor() == Factor.PHONE_OTP
                ? List.of(Factor.PHONE_OTP)
                : List.of(Factor.PHONE_OTP, created.secondFactor());
        sessions.signIn(created.account().id(), factors, created.sessionId(), request, response);
        return AuthResponses.Session.of(created.account(), factors).continuingTo(apps.resume(request));
    }

    private AuthResponses.RegistrationStep step(String step, PendingRegistration pending) {
        var otp = pending.otp();
        return new AuthResponses.RegistrationStep(
                step,
                pending.phone().display(),
                otp.secondsUntilResend(clock.instant(), props.otpResendAfter()),
                otp.channel().name().toLowerCase(Locale.ROOT));
    }
}
