package ca.northline.auth.web;

import ca.northline.auth.application.AccountService;
import ca.northline.auth.application.AuthProperties;
import ca.northline.auth.application.EnrollmentService;
import ca.northline.auth.application.InvalidInput;
import ca.northline.auth.application.SignupConsents;
import ca.northline.auth.domain.App;
import ca.northline.auth.domain.AuthMessages;
import ca.northline.auth.domain.Factor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-154, Create account with email + password (the Studio's "Create account" tab and its sell / provide / food entry
 * points, the consumer site's register page):
 *
 * <pre>
 * POST /api/auth/accounts               {firstName, lastName, email, password, passwordConfirm?, phone?, terms,
 *                                        termsLanguage?, termsEnglishRequested?, app?,
 *                                        marketing?: [{category, wordingVersion}]}   (S-157, the ticked boxes)
 *                                        → 202 {step:"verify-email", email, resendAfterSeconds} (always the same answer)
 * POST /api/auth/accounts/resend        → 202 {step:"verify-email", …}; 429 otp_throttled inside the cool-down
 * POST /api/auth/accounts/verify        {code}  → 201 session (signed in with the password; needsSecondFactor)
 * POST /api/auth/accounts/verify-link   {token} → {email, signedIn, session?} (signed in only in the browser that
 *                                        sent the form)
 * GET  /api/auth/accounts/consent-wordings?app=consumer|studio   → {requester, items:[{category, channel,
 *                                        wordingVersion, wording}]}  (S-157: the form's marketing checkboxes)
 * </pre>
 *
 * Then {@code /api/auth/enroll/*} ({@link EnrollmentController}) sets up the second factor.
 */
@RestController
@RequestMapping(path = "/api/auth/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
class AccountController {

    static final String VERIFY_EMAIL = "verify-email";

    private final AccountService accounts;
    private final EnrollmentService enrollment;
    private final SessionSignIn sessions;
    private final AppAuthorizationResume apps;
    private final AuthProperties props;
    private final SignupConsents consents;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    AuthResponses.EmailSent create(@Valid @RequestBody AuthRequests.CreateAccount body, HttpServletRequest request) {
        var password = Objects.requireNonNull(body.password());
        requireConfirmed(password, body.passwordConfirm());
        var sent = accounts.register(new AccountService.Register(
                Objects.requireNonNull(body.firstName()),
                Objects.requireNonNull(body.lastName()),
                Objects.requireNonNull(body.email()),
                password,
                body.phone(),
                app(body.app()),
                body.termsLanguage(),
                Boolean.TRUE.equals(body.termsEnglishRequested()),
                AuthRequests.requested(body.marketing()),
                Clients.of(request)));
        return new AuthResponses.EmailSent(VERIFY_EMAIL, sent.email(), sent.resendAfterSeconds());
    }

    /**
     * S-157: the marketing checkboxes the Create account form shows (unticked), in the request's language: per
     * category the wording and its version, and who asks. {@code app}: {@code consumer} (email and texts) or {@code
     * studio} (email); the console offers none.
     */
    @GetMapping("/consent-wordings")
    SignupConsents.Wordings consentWordings(@RequestParam(required = false) @Nullable String app, Locale locale) {
        return consents.wordings(app(app), "fr".equals(locale.getLanguage()) ? "fr" : "en");
    }

    @PostMapping("/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AuthResponses.EmailSent resend() {
        var sent = accounts.resend();
        return new AuthResponses.EmailSent(VERIFY_EMAIL, sent.email(), sent.resendAfterSeconds());
    }

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.CREATED)
    AuthResponses.Session verify(
            @Valid @RequestBody AuthRequests.Code body, HttpServletRequest request, HttpServletResponse response) {
        var confirmed = accounts.confirmCode(Objects.requireNonNull(body.code()), Clients.of(request));
        return signIn(confirmed, Objects.requireNonNull(confirmed.sessionId()), request, response);
    }

    @PostMapping("/verify-link")
    AuthResponses.EmailConfirmed verifyLink(
            @Valid @RequestBody AuthRequests.Token body, HttpServletRequest request, HttpServletResponse response) {
        var confirmed = accounts.confirmLink(Objects.requireNonNull(body.token()), Clients.of(request));
        var email = Objects.requireNonNullElse(confirmed.account().email(), "");
        var sessionId = confirmed.sessionId();
        return sessionId == null
                ? new AuthResponses.EmailConfirmed(email, false, null)
                : new AuthResponses.EmailConfirmed(email, true, signIn(confirmed, sessionId, request, response));
    }

    private AuthResponses.Session signIn(
            AccountService.Confirmed confirmed,
            String sessionId,
            HttpServletRequest request,
            HttpServletResponse response) {
        var factors = List.of(Factor.PASSWORD);
        sessions.signIn(confirmed.account().id(), factors, sessionId, request, response);
        return AuthResponses.Session.of(confirmed.account(), factors, props.platformZone())
                .needingSecondFactor(
                        enrollment.needsSecondFactor(confirmed.account().id()))
                .continuingTo(apps.resume(request));
    }

    static App app(@Nullable String code) {
        return code == null ? App.STUDIO : App.of(code).orElse(App.STUDIO);
    }

    static void requireConfirmed(String password, @Nullable String confirm) {
        if (confirm != null && !confirm.equals(password)) {
            throw InvalidInput.of("passwordConfirm", "mismatch", AuthMessages.PASSWORD_CONFIRM);
        }
    }
}
