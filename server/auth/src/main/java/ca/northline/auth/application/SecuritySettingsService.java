package ca.northline.auth.application;

import ca.northline.auth.application.FlowRejected.Reason;
import ca.northline.auth.domain.AuthMessages;
import ca.northline.auth.domain.Factor;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Studio Settings › Security for the signed-in person: what they have set up, adding another passkey or FIDO2
 * security key (same WebAuthn ceremony as registration, bound to the auth session's user) and removing one (S-19).
 */
@Service
@RequiredArgsConstructor
public class SecuritySettingsService {

    public static final int SIGN_INS_SHOWN = 10;
    public static final int LABEL_MAX = 60;

    private final AccountSecurity security;
    private final UserAccounts accounts;
    private final PasskeyService passkeys;
    private final FlowStore flow;
    private final SecurityChanges changes;
    private final AuditTrail audit;
    private final SecondFactors factors;
    private final BackupCodeService backupCodes;
    private final AttemptLimits limits;
    private final AuthProperties props;
    private final java.time.Clock clock;

    public record Overview(
            UserAccount account,
            List<AccountSecurity.Passkey> passkeys,
            java.time.@Nullable Instant authenticatorSince,
            AccountSecurity.BackupCodes backupCodes,
            List<AccountSecurity.SignIn> signIns) {}

    @Transactional(readOnly = true)
    public Overview overview(String userId) {
        var account = account(userId);
        return new Overview(
                account,
                security.passkeys(userId),
                security.authenticatorSince(userId),
                security.backupCodes(userId),
                security.recentSignIns(userId, SIGN_INS_SHOWN));
    }

    /** Options for {@code navigator.credentials.create()} (kept in the auth session until the browser answers). */
    public String passkeyOptions(String userId) {
        var account = account(userId);
        var name = (account.givenName() + " " + account.familyName()).strip();
        var options = passkeys.creationOptions(
                userId, Objects.requireNonNullElse(account.email(), userId), name.isEmpty() ? userId : name);
        flow.put(FlowStore.PASSKEY_CREATION, options);
        return passkeys.toJson(options);
    }

    @Transactional
    public List<AccountSecurity.Passkey> addPasskey(String userId, String credentialJson, @Nullable String label) {
        var options = flow.get(FlowStore.PASSKEY_CREATION)
                .orElseThrow(() -> new FlowRejected(Reason.NOT_STARTED, "Start adding the key again."));
        var name = label == null || label.isBlank() ? "Security key" : label.strip();
        passkeys.register(options, credentialJson, name.length() > LABEL_MAX ? name.substring(0, LABEL_MAX) : name);
        flow.remove(FlowStore.PASSKEY_CREATION);
        return security.passkeys(userId);
    }

    /**
     * Removes a passkey (S-19) — never the last second factor: another passkey or the authenticator app must remain.
     * Backup codes don't count (they are for recovery, not a way to sign in every day). When the last passkey goes, the
     * authenticator becomes the primary factor.
     */
    @Transactional
    public List<AccountSecurity.Passkey> removePasskey(Caller caller, String credentialId) {
        changes.authorize(caller);
        var userId = caller.userId();
        security.lockFactors(userId);
        var passkeys = security.passkeys(userId);
        var target = passkeys.stream()
                .filter(p -> p.id().equals(credentialId))
                .findFirst()
                .orElseThrow(() -> new FlowRejected(Reason.GONE, AuthMessages.PASSKEY_GONE));
        var authenticator = security.authenticatorSince(userId) != null;
        var left = passkeys.size() - 1;
        if (left + (authenticator ? 1 : 0) < 1) {
            throw new FlowRejected(Reason.LAST_FACTOR, AuthMessages.LAST_FACTOR);
        }
        if (!security.removePasskey(userId, credentialId)) {
            throw new FlowRejected(Reason.GONE, AuthMessages.PASSKEY_GONE);
        }
        if (left == 0) {
            security.setMfaPrimary(userId, Factor.TOTP.code());
        }
        audit.record(userId, "auth.passkey_removed", "passkey", credentialId, Map.of("label", target.label()));
        return security.passkeys(userId);
    }

    /** S-154: a new authenticator secret to scan (kept in the auth session until a code from it is confirmed). */
    public RegistrationService.TotpSetup authenticatorSetup(String userId) {
        var account = account(userId);
        var secret = TotpSetups.newSecret();
        flow.put(FlowStore.TOTP_ENROLMENT, secret);
        return TotpSetups.of(secret, Objects.requireNonNullElse(account.email(), userId), props.totpIssuer());
    }

    /**
     * S-154: sets up the authenticator app again (a new phone) or for the first time — a recent second factor
     * (step-up) and a code from the new secret. The old secret stops working at once.
     */
    @Transactional(noRollbackFor = {InvalidInput.class, FlowRejected.class})
    public void replaceAuthenticator(Caller caller, String code) {
        changes.authorize(caller);
        var userId = caller.userId();
        var account = account(userId);
        var secret = flow.get(FlowStore.TOTP_ENROLMENT)
                .orElseThrow(() -> new FlowRejected(Reason.NOT_STARTED, "Scan the QR code first."));
        var who = AttemptLimits.Subject.user(userId);
        limits.guard(LimitedAction.TOTP_VERIFY, who);
        var step = ca.northline.auth.domain.Totp.verify(secret, code.strip(), clock.instant(), Long.MIN_VALUE);
        if (step.isEmpty()) {
            throw limits.failed(
                    LimitedAction.TOTP_VERIFY, who, InvalidInput.of("code", "mismatch", AuthMessages.CODE_WRONG));
        }
        limits.succeeded(LimitedAction.TOTP_VERIFY, who);
        var replaced = security.authenticatorSince(userId) != null;
        factors.saveTotp(userId, secret, clock.instant());
        factors.markTotpUsed(userId, step.getAsLong());
        flow.remove(FlowStore.TOTP_ENROLMENT);
        if (account.mfaPrimary() == null || "sms".equals(account.mfaPrimary())) {
            security.setMfaPrimary(userId, Factor.TOTP.code());
        }
        audit.record(
                userId,
                replaced ? "auth.authenticator_replaced" : "auth.authenticator_added",
                "user",
                userId,
                Map.of("factor", Factor.TOTP.code()));
    }

    /** Ten new backup codes (the old ones stop working) — S-154: a recent second factor first (step-up). */
    @Transactional
    public List<String> regenerateBackupCodes(Caller caller) {
        changes.authorize(caller);
        var codes = backupCodes.regenerate(caller.userId());
        audit.record(
                caller.userId(),
                "auth.backup_codes_regenerated",
                "user",
                caller.userId(),
                Map.of("count", codes.size()));
        return codes;
    }

    private UserAccount account(String userId) {
        return accounts.findById(userId).orElseThrow(() -> new FlowRejected(Reason.UNAUTHENTICATED, "Sign in again."));
    }
}
