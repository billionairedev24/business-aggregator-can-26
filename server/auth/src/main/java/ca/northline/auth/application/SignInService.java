package ca.northline.auth.application;

import ca.northline.auth.application.FlowRejected.Reason;
import ca.northline.auth.application.SignInLog.Client;
import ca.northline.auth.domain.AuthMessages;
import ca.northline.auth.domain.BackupCodes;
import ca.northline.auth.domain.Factor;
import ca.northline.auth.domain.PhoneNumber;
import ca.northline.auth.domain.SignInAttempt;
import ca.northline.auth.domain.Totp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign in (design 02, "Sign in" tab): email or mobile → second factor (passkey, authenticator code or one of the
 * printed backup codes) → signed in. An unknown email/mobile goes through the same steps and fails at the factor, so
 * the form never tells whether an account exists.
 */
@Service
@RequiredArgsConstructor
public class SignInService {

    /** The factors the Studio offers, in the design's order. */
    public static final List<Factor> OFFERED = List.of(Factor.PASSKEY, Factor.TOTP, Factor.BACKUP_CODE);

    /** A user id no account has (ULIDs are 26 characters): the lookups for unknown accounts find nothing. */
    private static final String NO_ACCOUNT = "-";

    /** Checked when there is no real secret, so every attempt costs the same (RFC 4648 base32, 32 characters). */
    private static final SecondFactors.StoredTotp DECOY_TOTP =
            new SecondFactors.StoredTotp("NORTHLINEDECOYSECRETNORTHLINEDEC", Long.MIN_VALUE);

    private final UserAccounts accounts;
    private final SecondFactors factors;
    private final PasskeyService passkeys;
    private final FlowStore flow;
    private final SignInLog signIns;
    private final AttemptLimits limits;
    private final FederatedLinking federation;
    private final Clock clock;

    /** Signed in: who, with which second factor, and the new session's id ({@code identity.sessions}). */
    public record SignedIn(UserAccount account, Factor factor, String sessionId) {}

    public SignInAttempt start(String identifier) {
        var id = identifier.trim();
        limits.consume(LimitedAction.SIGN_IN_LOOKUP, AttemptLimits.Subject.identifier(id, null));
        var userId = lookup(id).filter(UserAccount::active).map(UserAccount::id).orElse(null);
        var attempt = new SignInAttempt(id, userId, 0);
        flow.put(FlowStore.SIGN_IN, attempt);
        flow.remove(FlowStore.PASSKEY_REQUEST);
        flow.remove(FlowStore.SIGN_IN_CODE); // S-62: a code sent for an earlier identifier no longer counts
        return attempt;
    }

    /**
     * Options for {@code navigator.credentials.get()}: the same for everyone (S-20: no credential ids of the typed
     * account). Without a started attempt (the "Passkey" button next to Google/Apple) any discoverable passkey is
     * accepted; with one, the passkey must belong to the typed account.
     */
    public String passkeyOptions() {
        var options = passkeys.signInOptions();
        flow.put(FlowStore.PASSKEY_REQUEST, options);
        return passkeys.toJson(options);
    }

    @Transactional
    public SignedIn completeWithPasskey(String assertionJson, Client client) {
        var attempt = flow.get(FlowStore.SIGN_IN).orElse(null);
        guard(attempt);
        var who = subject(attempt);
        limits.guard(LimitedAction.PASSKEY_ASSERTION, who);
        var options = flow.get(FlowStore.PASSKEY_REQUEST)
                .orElseThrow(() -> new FlowRejected(Reason.NOT_STARTED, "Start the passkey sign-in again."));
        flow.remove(FlowStore.PASSKEY_REQUEST);
        String userId;
        try {
            userId = passkeys.authenticate(options, assertionJson);
        } catch (InvalidInput e) {
            throw limits.failed(
                    LimitedAction.PASSKEY_ASSERTION,
                    who,
                    failure(attempt, attempt == null ? null : attempt.userId(), Factor.PASSKEY, e, client));
        }
        if (attempt != null && attempt.userId() != null && !attempt.userId().equals(userId)) {
            throw limits.failed(
                    LimitedAction.PASSKEY_ASSERTION,
                    who,
                    failure(attempt, attempt.userId(), Factor.PASSKEY, passkeyMismatch(), client));
        }
        var account = accounts.findById(userId).filter(UserAccount::active);
        if (account.isEmpty()) {
            throw limits.failed(
                    LimitedAction.PASSKEY_ASSERTION,
                    who,
                    failure(attempt, userId, Factor.PASSKEY, passkeyMismatch(), client));
        }
        limits.succeeded(LimitedAction.PASSKEY_ASSERTION, who);
        return succeed(account.get(), Factor.PASSKEY, client);
    }

    @Transactional
    public SignedIn verifyTotp(String code, Client client) {
        var attempt = requireAttempt();
        var who = subject(attempt);
        limits.guard(LimitedAction.TOTP_VERIFY, who);
        var userId = attempt.userId();
        // S-20: an unknown account (or one without an authenticator) does the same work — a lookup and a code
        // check against a decoy secret — so the answer's timing doesn't tell it apart from a wrong code.
        var stored = factors.findTotp(userId == null ? NO_ACCOUNT : userId);
        var checked = stored.orElse(DECOY_TOTP);
        var verified = Totp.verify(checked.secret(), code.trim(), clock.instant(), checked.lastUsedStep());
        var step =
                stored.isPresent() && verified.isPresent() ? Optional.of(verified.getAsLong()) : Optional.<Long>empty();
        if (userId == null || step.isEmpty() || !factors.markTotpUsed(userId, step.get())) {
            throw limits.failed(
                    LimitedAction.TOTP_VERIFY,
                    who,
                    failure(
                            attempt,
                            userId,
                            Factor.TOTP,
                            InvalidInput.of("code", "mismatch", AuthMessages.SIGN_IN_CODE_WRONG),
                            client));
        }
        limits.succeeded(LimitedAction.TOTP_VERIFY, who);
        return succeed(accounts.findById(userId).orElseThrow(), Factor.TOTP, client);
    }

    @Transactional
    public SignedIn verifyBackupCode(String code, Client client) {
        var attempt = requireAttempt();
        var who = subject(attempt);
        limits.guard(LimitedAction.BACKUP_CODE_VERIFY, who);
        var userId = attempt.userId();
        // S-20: the same query for an unknown account (it matches nothing), so timing doesn't reveal the account.
        var consumed = factors.consumeBackupCode(
                userId == null ? NO_ACCOUNT : userId, BackupCodes.hash(code), clock.instant());
        if (userId == null || !consumed) {
            throw limits.failed(
                    LimitedAction.BACKUP_CODE_VERIFY,
                    who,
                    failure(
                            attempt,
                            userId,
                            Factor.BACKUP_CODE,
                            InvalidInput.of("code", "mismatch", AuthMessages.BACKUP_CODE_WRONG),
                            client));
        }
        limits.succeeded(LimitedAction.BACKUP_CODE_VERIFY, who);
        return succeed(accounts.findById(userId).orElseThrow(), Factor.BACKUP_CODE, client);
    }

    private Optional<UserAccount> lookup(String identifier) {
        if (identifier.contains("@")) {
            return accounts.findByEmail(identifier);
        }
        return PhoneNumber.parse(identifier).flatMap(p -> accounts.findByPhone(p.e164()));
    }

    private SignInAttempt requireAttempt() {
        var attempt = flow.get(FlowStore.SIGN_IN)
                .orElseThrow(() -> new FlowRejected(Reason.NOT_STARTED, "Enter your email or mobile first."));
        guard(attempt);
        return attempt;
    }

    private static void guard(@Nullable SignInAttempt attempt) {
        if (attempt != null && attempt.locked()) {
            throw new FlowRejected(Reason.LOCKED, AuthMessages.TOO_MANY_ATTEMPTS);
        }
    }

    private RuntimeException failure(
            @Nullable SignInAttempt attempt,
            @Nullable String userId,
            Factor factor,
            InvalidInput error,
            Client client) {
        signIns.failed(userId, factor, error.getViolations().getFirst().rule(), client);
        if (attempt == null) {
            return error;
        }
        var next = attempt.failed();
        flow.put(FlowStore.SIGN_IN, next);
        return next.locked() ? new FlowRejected(Reason.LOCKED, AuthMessages.TOO_MANY_ATTEMPTS) : error;
    }

    private SignedIn succeed(UserAccount account, Factor factor, Client client) {
        if (account.erased()) {
            throw new FlowRejected(Reason.UNAUTHENTICATED, AuthMessages.ACCOUNT_DELETED);
        }
        flow.remove(FlowStore.SIGN_IN);
        var sessionId = signIns.succeeded(account.id(), factor.code(), factor.isSecondFactor(), client);
        federation.complete(account.id(), false); // S-18: "Continue with Google/Apple" waiting for this factor
        return new SignedIn(account, factor, sessionId);
    }

    /** The account as typed on the form (unknown accounts count the same); nobody for a passkey without a form. */
    private static AttemptLimits.Subject subject(@Nullable SignInAttempt attempt) {
        return attempt == null
                ? AttemptLimits.Subject.NOBODY
                : AttemptLimits.Subject.identifier(attempt.identifier(), attempt.userId());
    }

    private static InvalidInput passkeyMismatch() {
        return InvalidInput.of("credential", "passkey", AuthMessages.PASSKEY_FAILED);
    }
}
