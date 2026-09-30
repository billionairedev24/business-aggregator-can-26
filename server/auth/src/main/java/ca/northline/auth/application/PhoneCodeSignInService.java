package ca.northline.auth.application;

import static ca.northline.auth.domain.AuthMessages.CODE_EXPIRED;
import static ca.northline.auth.domain.AuthMessages.CODE_LOCKED;
import static ca.northline.auth.domain.AuthMessages.SIGN_IN_CODE_WRONG;

import ca.northline.auth.application.FlowRejected.Reason;
import ca.northline.auth.application.SignInLog.Client;
import ca.northline.auth.domain.AuthMessages;
import ca.northline.auth.domain.Factor;
import ca.northline.auth.domain.OtpChallenge;
import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PhoneNumber;
import ca.northline.auth.domain.SignInAttempt;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-62, the consumer site's sign-in (design 06 `auth`): mobile (or email) → a 6-digit code to the account's phone →
 * signed in with that one factor ({@code phone_otp}; no {@code acr=mfa}). Starts from the same
 * {@code POST /api/auth/sign-in} as the Studio's flow. An unknown account gets the same answers (an unsent code, the
 * same cool-down, "That code didn't work"), so the form never tells whether an account exists. Codes follow the
 * registration's rules (6 digits, 10 min, resend after 45 s, voice fallback, 5 tries) and its S-9 limits
 * ({@code otp-send}, {@code otp-verify}). Business clients never get a code for such a sign-in
 * ({@code northline.auth.mfa-required-clients}).
 */
@Service
@RequiredArgsConstructor
public class PhoneCodeSignInService {

    private final FlowStore flow;
    private final UserAccounts accounts;
    private final PhoneCodes codes;
    private final AttemptLimits limits;
    private final SignInLog signIns;
    private final AuthProperties props;
    private final Clock clock;

    /** A code is on its way (or, for an unknown account, seems to be). */
    public record CodeSent(long resendAfterSeconds, Channel channel) {}

    /** Sends the code (first call and "Resend" by SMS after 45 s, or "Call me instead" once per SMS code). */
    public CodeSent send(Channel channel) {
        var attempt = requireAttempt();
        var who = subject(attempt);
        var current = flow.get(FlowStore.SIGN_IN_CODE);
        if (current.isPresent()) {
            var otp = current.get();
            long wait = otp.secondsUntilResend(clock.instant(), props.otpResendAfter());
            boolean voiceFallback = channel == Channel.VOICE && otp.channel() == Channel.SMS;
            if (wait > 0 && !voiceFallback) {
                throw new FlowRejected(
                        Reason.THROTTLED, "Wait %d s before sending another code.".formatted(wait), wait);
            }
        }
        limits.consume(LimitedAction.OTP_SEND, who);
        var phone = attempt.userId() == null
                ? null
                : accounts.findById(attempt.userId())
                        .filter(UserAccount::active)
                        .map(UserAccount::phone)
                        .flatMap(PhoneNumber::parse)
                        .orElse(null);
        var otp = phone == null ? codes.unsent(channel) : codes.send(phone, channel, false);
        flow.put(FlowStore.SIGN_IN_CODE, otp);
        return new CodeSent(otp.secondsUntilResend(clock.instant(), props.otpResendAfter()), otp.channel());
    }

    /** The code: signed in with the phone as the only factor. */
    @Transactional
    public SignInService.SignedIn verify(String code, Client client) {
        var attempt = requireAttempt();
        var otp = flow.get(FlowStore.SIGN_IN_CODE)
                .orElseThrow(() -> new FlowRejected(Reason.NOT_STARTED, "Send a code first."));
        var who = subject(attempt);
        limits.guard(LimitedAction.OTP_VERIFY, who);
        var userId = attempt.userId();
        return switch (otp.check(code.trim(), clock.instant(), props.otpMaxAttempts())) {
            case OtpChallenge.Check.Verified _
            when userId != null -> {
                flow.remove(FlowStore.SIGN_IN_CODE);
                flow.remove(FlowStore.SIGN_IN);
                limits.succeeded(LimitedAction.OTP_VERIFY, who);
                var account = accounts.findById(userId).orElseThrow();
                var sessionId = signIns.succeeded(userId, Factor.PHONE_OTP.code(), false, client);
                yield new SignInService.SignedIn(account, Factor.PHONE_OTP, sessionId);
            }
            // An unknown account's unsent code guessed right: still no account.
            case OtpChallenge.Check.Verified _ -> throw wrong(attempt, otp, null, client);
            case OtpChallenge.Check.Wrong(var next) -> throw wrong(attempt, next, userId, client);
            case OtpChallenge.Check.Expired _ -> throw InvalidInput.of("code", "expired", CODE_EXPIRED);
            case OtpChallenge.Check.Locked(var next) -> {
                flow.put(FlowStore.SIGN_IN_CODE, next);
                signIns.failed(userId, Factor.PHONE_OTP, "locked", client);
                throw limits.failed(LimitedAction.OTP_VERIFY, who, InvalidInput.of("code", "locked", CODE_LOCKED));
            }
        };
    }

    private RuntimeException wrong(SignInAttempt attempt, OtpChallenge next, @Nullable String userId, Client client) {
        flow.put(FlowStore.SIGN_IN_CODE, next);
        signIns.failed(userId, Factor.PHONE_OTP, "mismatch", client);
        return limits.failed(
                LimitedAction.OTP_VERIFY, subject(attempt), InvalidInput.of("code", "mismatch", SIGN_IN_CODE_WRONG));
    }

    private SignInAttempt requireAttempt() {
        var attempt = flow.get(FlowStore.SIGN_IN)
                .orElseThrow(() -> new FlowRejected(Reason.NOT_STARTED, "Enter your email or mobile first."));
        if (attempt.locked()) {
            throw new FlowRejected(Reason.LOCKED, AuthMessages.TOO_MANY_ATTEMPTS);
        }
        return attempt;
    }

    private static AttemptLimits.Subject subject(SignInAttempt attempt) {
        return AttemptLimits.Subject.identifier(attempt.identifier(), attempt.userId());
    }
}
