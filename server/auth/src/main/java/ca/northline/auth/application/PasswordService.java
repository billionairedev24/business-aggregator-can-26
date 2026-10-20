package ca.northline.auth.application;

import ca.northline.auth.application.EmailCodes.Purpose;
import ca.northline.auth.application.EmailCodes.StoredCode;
import ca.northline.auth.application.FlowRejected.Reason;
import ca.northline.auth.application.SignInSessions.EndReason;
import ca.northline.auth.domain.App;
import ca.northline.auth.domain.AuthMessages;
import ca.northline.auth.domain.EmailCode;
import ca.northline.auth.domain.ResetRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-154 passwords: "Forgot password" by email (a 6-digit code and a link, 15 min, single use; every session of the
 * account ends; the next sign-in still asks for the second factor — or enrols one), and "Change password" in Settings ›
 * Security (a recent second factor, the current password when there is one; every other session ends).
 *
 * <p>Forgot password answers the same for an unknown address (nothing is sent; the codes typed for it are wrong). An
 * older account that never had a password sets its first one this way.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordService {

    private final UserAccounts accounts;
    private final Passwords passwords;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final EmailCodes codes;
    private final EmailCodeGate gate;
    private final AccountEmails emails;
    private final FlowStore flow;
    private final AttemptLimits limits;
    private final SecurityChanges changes;
    private final SessionService sessions;
    private final AuditTrail audit;
    private final AccountProperties props;
    private final Clock clock;

    /** The email is on its way (or seems to be). */
    public record Sent(String email, long resendAfterSeconds) {}

    /** The password was reset: the address to sign in with. */
    public record Reset(String email) {}

    @Transactional
    public Sent forgot(String email, App app) {
        var address = email.strip();
        var now = clock.instant();
        var open = flow.get(FlowStore.RESET).filter(r -> r.email().equalsIgnoreCase(address));
        if (open.isPresent()) {
            var wait = secondsUntilResend(open.get().sentAt(), now);
            if (wait > 0) {
                throw new FlowRejected(
                        Reason.THROTTLED, "Wait %d s before sending another email.".formatted(wait), wait);
            }
        }
        limits.consume(LimitedAction.EMAIL_SEND, AttemptLimits.Subject.identifier(address, null));
        var account = accounts.findByEmail(address).filter(UserAccount::active);
        account.ifPresent(a -> send(a, app, now));
        flow.put(
                FlowStore.RESET,
                new ResetRequest(address, account.map(UserAccount::id).orElse(null), app, now));
        return new Sent(address, props.resendAfter().toSeconds());
    }

    /** The code from the email (this browser asked) and the new password. */
    @Transactional(noRollbackFor = {InvalidInput.class, FlowRejected.class})
    public Reset resetWithCode(String code, String password) {
        var request = flow.get(FlowStore.RESET)
                .orElseThrow(() -> new FlowRejected(Reason.NOT_STARTED, "Ask for a new email first."));
        var userId = request.userId();
        var who = AttemptLimits.Subject.identifier(request.email(), userId);
        var stored = userId == null ? Optional.<StoredCode>empty() : codes.latest(userId, Purpose.RESET_PASSWORD);
        var valid = gate.check(stored, code, who);
        var reset = reset(valid, password, "code");
        gate.use(valid, who);
        flow.remove(FlowStore.RESET);
        return reset;
    }

    /** The email's link (any browser) and the new password. */
    @Transactional(noRollbackFor = {InvalidInput.class, FlowRejected.class})
    public Reset resetWithLink(String token, String password) {
        limits.guard(LimitedAction.EMAIL_CODE_VERIFY, AttemptLimits.Subject.NOBODY);
        var now = clock.instant();
        var stored = codes.byTokenHash(EmailCode.hashOfToken(token)).filter(c -> c.purpose() == Purpose.RESET_PASSWORD);
        if (stored.isEmpty()) {
            throw limits.failed(
                    LimitedAction.EMAIL_CODE_VERIFY,
                    AttemptLimits.Subject.NOBODY,
                    InvalidInput.of("token", "expired", AuthMessages.LINK_EXPIRED));
        }
        var c = stored.get();
        if (c.usedAt() != null || !c.expiresAt().isAfter(now)) {
            throw InvalidInput.of("token", "expired", AuthMessages.LINK_EXPIRED);
        }
        var reset = reset(c, password, "link");
        if (!codes.use(c.id(), now)) {
            throw InvalidInput.of("token", "expired", AuthMessages.LINK_EXPIRED);
        }
        flow.remove(FlowStore.RESET);
        return reset;
    }

    /**
     * Settings › Security: a second factor in the last few minutes (step-up) and the current password when the
     * account has one. Every other session ends.
     */
    @Transactional(noRollbackFor = {InvalidInput.class, FlowRejected.class})
    public void change(Caller caller, @Nullable String current, String password, @Nullable String alsoCurrent) {
        changes.authorize(caller);
        var userId = caller.userId();
        var account =
                accounts.findById(userId).orElseThrow(() -> new FlowRejected(Reason.UNAUTHENTICATED, "Sign in again."));
        var existing = passwords.hashOf(userId);
        if (existing.isPresent()) {
            var who = AttemptLimits.Subject.user(userId);
            limits.guard(LimitedAction.PASSWORD_VERIFY, who);
            if (current == null || current.isEmpty()) {
                throw InvalidInput.of("currentPassword", "required", AuthMessages.CURRENT_PASSWORD_REQUIRED);
            }
            if (!hasher.matches(PasswordPolicy.normalise(current), existing.get())) {
                throw limits.failed(
                        LimitedAction.PASSWORD_VERIFY,
                        who,
                        InvalidInput.of("currentPassword", "mismatch", AuthMessages.CURRENT_PASSWORD_WRONG));
            }
            limits.succeeded(LimitedAction.PASSWORD_VERIFY, who);
        }
        policy.require("password", password, account.email());
        var now = clock.instant();
        passwords.save(userId, hasher.hash(PasswordPolicy.normalise(password)), now);
        var ended = sessions.endOthers(caller, alsoCurrent, EndReason.PASSWORD_CHANGED);
        audit.record(
                userId,
                existing.isPresent() ? "auth.password_changed" : "auth.password_set",
                "user",
                userId,
                Map.of("sessionsEnded", ended));
    }

    /** Whether the account has a password (Settings › Security shows "Change" or "Set a password"). */
    public boolean hasPassword(String userId) {
        return passwords.hashOf(userId).isPresent();
    }

    private Reset reset(StoredCode code, String password, String via) {
        var account = accounts.findById(code.subjectId())
                .filter(UserAccount::active)
                .orElseThrow(() -> InvalidInput.of("code", "expired", AuthMessages.CODE_EXPIRED));
        policy.require("password", password, account.email());
        var now = clock.instant();
        passwords.save(account.id(), hasher.hash(PasswordPolicy.normalise(password)), now);
        accounts.emailVerified(account.id(), now); // reading the email proved the address
        var ended = sessions.endAll(account.id(), EndReason.PASSWORD_RESET);
        audit.record(
                account.id(), "auth.password_reset", "user", account.id(), Map.of("via", via, "sessionsEnded", ended));
        log.info("Password reset by email: user={} sessions ended={}", account.id(), ended);
        return new Reset(java.util.Objects.requireNonNullElse(account.email(), ""));
    }

    private void send(UserAccount account, App app, Instant now) {
        var issued = EmailCode.issue();
        var id = AccountService.newId();
        codes.issue(new StoredCode(
                id,
                Purpose.RESET_PASSWORD,
                account.id(),
                app,
                issued.codeHash(),
                issued.tokenHash(),
                0,
                now,
                now.plus(props.codeTtl()),
                null));
        emails.passwordReset(
                new AccountEmails.Recipient(
                        java.util.Objects.requireNonNull(account.email()),
                        account.givenName(),
                        AccountService.localeOf(account),
                        app,
                        "reset:" + id),
                issued.code(),
                issued.token(),
                props.codeTtl().toMinutes());
    }

    private long secondsUntilResend(Instant sentAt, Instant now) {
        return Math.max(0, sentAt.plus(props.resendAfter()).getEpochSecond() - now.getEpochSecond());
    }
}
