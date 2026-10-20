package ca.northline.auth.application;

import ca.northline.auth.application.FlowRejected.Reason;
import ca.northline.auth.domain.AuthMessages;
import ca.northline.auth.domain.Factor;
import ca.northline.auth.domain.Totp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-154: the second factor of an account that has none — right after its email is confirmed, or at the next sign-in
 * of an older account (an account that needs one is sent here, never to a dead-end "second factor required" page).
 * Authenticator app (QR code with the {@code otpauth://} URI, issuer {@code Northline}, account = the email, the key to
 * type, then a code from the app) or a passkey; then ten backup codes, shown once.
 *
 * <p>Only for an account without a passkey or an authenticator: anyone who knows the password of an account that has
 * one must sign in with it (and change factors in Settings › Security with a step-up), never add their own here.
 */
@Service
@RequiredArgsConstructor
public class EnrollmentService {

    private final UserAccounts accounts;
    private final SecondFactors factors;
    private final AccountSecurity security;
    private final PasskeyService passkeys;
    private final BackupCodeService backupCodes;
    private final FlowStore flow;
    private final AttemptLimits limits;
    private final AuditTrail audit;
    private final AuthProperties props;
    private final Clock clock;

    /** The new factor and the backup codes to show once. */
    public record Enrolled(Factor factor, List<String> backupCodes) {}

    /** Whether the account still needs a first second factor. */
    public boolean needsSecondFactor(String userId) {
        return security.passkeys(userId).isEmpty() && security.authenticatorSince(userId) == null;
    }

    public RegistrationService.TotpSetup totpSetup(String userId) {
        var account = enrollable(userId);
        var secret = TotpSetups.newSecret();
        flow.put(FlowStore.TOTP_ENROLMENT, secret);
        return TotpSetups.of(secret, Objects.requireNonNullElse(account.email(), userId), props.totpIssuer());
    }

    @Transactional(noRollbackFor = {InvalidInput.class, FlowRejected.class})
    public Enrolled confirmTotp(String userId, String code) {
        security.lockFactors(userId);
        enrollable(userId);
        var secret = flow.get(FlowStore.TOTP_ENROLMENT)
                .orElseThrow(() -> new FlowRejected(Reason.NOT_STARTED, "Scan the QR code first."));
        var who = AttemptLimits.Subject.user(userId);
        limits.guard(LimitedAction.TOTP_VERIFY, who);
        var step = Totp.verify(secret, code.strip(), clock.instant(), Long.MIN_VALUE);
        if (step.isEmpty()) {
            throw limits.failed(
                    LimitedAction.TOTP_VERIFY, who, InvalidInput.of("code", "mismatch", AuthMessages.CODE_WRONG));
        }
        limits.succeeded(LimitedAction.TOTP_VERIFY, who);
        factors.saveTotp(userId, secret, clock.instant());
        factors.markTotpUsed(userId, step.getAsLong());
        flow.remove(FlowStore.TOTP_ENROLMENT);
        return enrolled(userId, Factor.TOTP);
    }

    public String passkeyOptions(String userId) {
        var account = enrollable(userId);
        var name = (account.givenName() + " " + account.familyName()).strip();
        var options = passkeys.creationOptions(
                userId, Objects.requireNonNullElse(account.email(), userId), name.isEmpty() ? userId : name);
        flow.put(FlowStore.PASSKEY_CREATION, options);
        return passkeys.toJson(options);
    }

    @Transactional
    public Enrolled confirmPasskey(String userId, String credentialJson, String label) {
        security.lockFactors(userId);
        enrollable(userId);
        var options = flow.get(FlowStore.PASSKEY_CREATION)
                .orElseThrow(() -> new FlowRejected(Reason.NOT_STARTED, "Start the passkey set-up again."));
        flow.remove(FlowStore.PASSKEY_CREATION);
        var name = label.isBlank() ? "Passkey" : label.strip();
        passkeys.register(
                options,
                credentialJson,
                name.length() > SecuritySettingsService.LABEL_MAX
                        ? name.substring(0, SecuritySettingsService.LABEL_MAX)
                        : name);
        return enrolled(userId, Factor.PASSKEY);
    }

    private Enrolled enrolled(String userId, Factor factor) {
        security.setMfaPrimary(userId, factor.code());
        var codes = backupCodes.regenerate(userId);
        audit.record(userId, "auth.factor_enrolled", "user", userId, Map.of("factor", factor.code()));
        return new Enrolled(factor, codes);
    }

    private UserAccount enrollable(String userId) {
        var account = accounts.findById(userId)
                .filter(UserAccount::active)
                .orElseThrow(() -> new FlowRejected(Reason.UNAUTHENTICATED, "Sign in again."));
        if (!needsSecondFactor(userId)) {
            throw new FlowRejected(Reason.ALREADY_ENROLLED, AuthMessages.ALREADY_ENROLLED);
        }
        return account;
    }
}
