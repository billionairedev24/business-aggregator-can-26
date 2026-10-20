package ca.northline.auth.web;

import ca.northline.auth.application.InvalidInput;
import ca.northline.auth.application.PasswordService;
import ca.northline.auth.domain.AuthMessages;
import jakarta.validation.Valid;
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
 * S-154, Forgot password (signed out):
 *
 * <pre>
 * POST /api/auth/password/forgot   {email, app?}                       → 202 {step:"verify-email", email,
 *                                                                        resendAfterSeconds} (the same for anyone)
 * POST /api/auth/password/reset    {code | token, password, passwordConfirm?} → {email}; every session ends
 * </pre>
 *
 * Signing in afterwards still asks for the second factor (or enrols one).
 */
@RestController
@RequestMapping(path = "/api/auth/password", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
class PasswordController {

    private final PasswordService passwords;

    @PostMapping("/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AuthResponses.EmailSent forgot(@Valid @RequestBody AuthRequests.Forgot body) {
        var sent = passwords.forgot(Objects.requireNonNull(body.email()), AccountController.app(body.app()));
        return new AuthResponses.EmailSent(AccountController.VERIFY_EMAIL, sent.email(), sent.resendAfterSeconds());
    }

    @PostMapping("/reset")
    AuthResponses.PasswordReset reset(@Valid @RequestBody AuthRequests.ResetPassword body) {
        var password = Objects.requireNonNull(body.password());
        AccountController.requireConfirmed(password, body.passwordConfirm());
        var token = body.token();
        var code = body.code();
        if (token != null && !token.isEmpty()) {
            return new AuthResponses.PasswordReset(
                    passwords.resetWithLink(token, password).email());
        }
        if (code == null || code.isEmpty()) {
            throw InvalidInput.of("code", "required", AuthMessages.CODE_REQUIRED);
        }
        return new AuthResponses.PasswordReset(
                passwords.resetWithCode(code, password).email());
    }
}
