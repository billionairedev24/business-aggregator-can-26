package ca.northline.auth.application;

import ca.northline.auth.application.EmailCodes.Purpose;
import ca.northline.auth.application.FlowRejected.Reason;
import ca.northline.auth.application.PendingAccounts.PendingAccount;
import ca.northline.auth.application.SignInLog.Client;
import ca.northline.auth.domain.App;
import ca.northline.auth.domain.AuthMessages;
import ca.northline.auth.domain.EmailCode;
import ca.northline.auth.domain.PhoneNumber;
import ca.northline.auth.domain.SignUp;
import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-154, Create account with an email and a password: form → a 6-digit code and a link by email (15 min, single use)
 * → the account exists ({@code identity.users}, password in {@code auth.passwords}) and this browser is signed in with
 * the password alone → the app enrols a second factor ({@link EnrollmentService}; required for the Studio and the
 * console, offered on the consumer site).
 *
 * <p>No account enumeration: the form's answer is the same for an address that already has an account — that address
 * gets an "you already have an account" email instead of a code, and every code typed for it is wrong. Until the code is
 * used the account is pending ({@code auth.pending_accounts}); registering the same address again replaces it, so
 * nobody can hold an address they can't read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserAccounts accounts;
    private final PendingAccounts pending;
    private final Passwords passwords;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final EmailCodes codes;
    private final EmailCodeGate gate;
    private final AccountEmails emails;
    private final FlowStore flow;
    private final SignInLog signIns;
    private final AttemptLimits limits;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final AuthProperties authProps;
    private final AccountProperties props;
    private final SignupConsents consents;
    private final Clock clock;

    /** The form, shape-validated by the web adapter (required fields, formats, terms accepted). */
    public record Register(
            String firstName,
            String lastName,
            String email,
            String password,
            @Nullable String phone,
            App app,
            @Nullable String termsLanguage,
            boolean termsEnglishRequested,
            List<SignupConsents.Requested> marketing,
            Client client) {

        /** The form without marketing checkboxes (tests, older clients). */
        public Register(
                String firstName,
                String lastName,
                String email,
                String password,
                @Nullable String phone,
                App app,
                @Nullable String termsLanguage,
                boolean termsEnglishRequested) {
            this(
                    firstName,
                    lastName,
                    email,
                    password,
                    phone,
                    app,
                    termsLanguage,
                    termsEnglishRequested,
                    List.of(),
                    new Client(null, null));
        }
    }

    /** The email is on its way (or seems to be): where, and when another may be asked for. */
    public record Sent(String email, long resendAfterSeconds) {}

    /**
     * The address is confirmed and the account exists. {@code sessionId} is set when this browser sent the form (it is
     * then signed in with the password); a link opened elsewhere only confirms — that browser signs in normally.
     */
    public record Confirmed(
            UserAccount account, App app, @Nullable String sessionId) {}

    @Transactional
    public Sent register(Register in) {
        var email = in.email().strip();
        policy.require("password", in.password(), email);
        // Every submission counts, also for an address that has an account: the answer must not differ.
        limits.consume(LimitedAction.EMAIL_SEND, AttemptLimits.Subject.identifier(email, null));
        var hash = hasher.hash(PasswordPolicy.normalise(in.password())); // same work either way
        var now = clock.instant();
        // S-157: the ticked marketing boxes, checked against the wording the form showed (a text one needs a mobile) —
        // before the existing-account branch, so a refusal never tells the two apart
        var typedPhone = Optional.ofNullable(in.phone())
                .filter(p -> !p.isBlank())
                .flatMap(PhoneNumber::parse)
                .map(PhoneNumber::e164);
        var marketing =
                consents.accept(in.marketing(), in.app(), language(), in.client(), typedPhone.orElse(null), now);
        var existing = accounts.findByEmail(email);
        if (existing.isPresent()) {
            var account = existing.get();
            emails.accountExists(recipient(email, account.givenName(), localeOf(account), in.app()));
            flow.put(FlowStore.SIGN_UP, new SignUp(null, email, in.app(), now));
            log.info("Create account with the address of an existing account: told by email (user={})", account.id());
            return new Sent(email, props.resendAfter().toSeconds());
        }
        var phone = typedPhone
                .filter(p -> !accounts.anyPhone(p)) // no enumeration: a number in use is simply not stored
                .orElse(null);
        var id = pending.save(new PendingAccount(
                pending.findByEmail(email).map(PendingAccount::id).orElseGet(AccountService::newId),
                email,
                in.firstName().strip(),
                in.lastName().strip(),
                phone,
                hash,
                requestLocale(),
                in.app(),
                authProps.termsVersion(),
                now,
                in.termsEnglishRequested() ? "en" : in.termsLanguage(),
                in.termsEnglishRequested() ? now : null,
                now.plus(props.pendingTtl()),
                // a text consent whose number isn't stored (in use elsewhere) covers nothing: email ones only then
                phone == null
                        ? marketing.stream()
                                .filter(c -> !"marketing_sms".equals(c.category()))
                                .toList()
                        : marketing));
        var account = pending.findById(id).orElseThrow();
        sendVerification(account, now);
        flow.put(FlowStore.SIGN_UP, new SignUp(id, email, in.app(), now));
        return new Sent(email, props.resendAfter().toSeconds());
    }

    /** "Send a new code" (after the cool-down). */
    @Transactional
    public Sent resend() {
        var signUp = current();
        var now = clock.instant();
        throttle(signUp.sentAt(), now);
        limits.consume(LimitedAction.EMAIL_SEND, AttemptLimits.Subject.identifier(signUp.email(), null));
        var id = signUp.pendingId();
        var account = id == null ? Optional.<PendingAccount>empty() : pending.findById(id);
        if (account.isPresent()) {
            sendVerification(account.get(), now);
        } else {
            accounts.findByEmail(signUp.email())
                    .ifPresent(a ->
                            emails.accountExists(recipient(signUp.email(), a.givenName(), localeOf(a), signUp.app())));
        }
        flow.put(FlowStore.SIGN_UP, signUp.resent(now));
        return new Sent(signUp.email(), props.resendAfter().toSeconds());
    }

    /** Sign in with the password of an account still pending: a new code (unless one was sent moments ago). */
    @Transactional
    public Sent resendFor(PendingAccount account) {
        var now = clock.instant();
        var open = flow.get(FlowStore.SIGN_UP).filter(s -> account.id().equals(s.pendingId()));
        if (open.isEmpty() || secondsUntilResend(open.get().sentAt(), now) == 0) {
            limits.consume(LimitedAction.EMAIL_SEND, AttemptLimits.Subject.identifier(account.email(), null));
            sendVerification(account, now);
            flow.put(FlowStore.SIGN_UP, new SignUp(account.id(), account.email(), account.app(), now));
            return new Sent(account.email(), props.resendAfter().toSeconds());
        }
        return new Sent(account.email(), secondsUntilResend(open.get().sentAt(), now));
    }

    /** The 6-digit code typed in the browser that sent the form: account created, this browser signed in. */
    @Transactional(noRollbackFor = {InvalidInput.class, FlowRejected.class})
    public Confirmed confirmCode(String code, Client client) {
        var signUp = current();
        var who = AttemptLimits.Subject.identifier(signUp.email(), null);
        var id = signUp.pendingId();
        var account = id == null ? Optional.<PendingAccount>empty() : pending.findById(id);
        var valid = gate.check(account.flatMap(a -> codes.latest(a.id(), Purpose.VERIFY_EMAIL)), code, who);
        gate.use(valid, who);
        var created = create(account.orElseThrow(), "code", clock.instant());
        flow.remove(FlowStore.SIGN_UP);
        var sessionId = signIns.succeeded(created.id(), "registration", false, client);
        return new Confirmed(created, account.get().app(), sessionId);
    }

    /**
     * The email's link. Confirms the address wherever it is opened; signs in only the browser that sent the form —
     * anywhere else the person signs in with their password (a link alone must never let someone enrol a factor).
     */
    @Transactional(noRollbackFor = {InvalidInput.class, FlowRejected.class})
    public Confirmed confirmLink(String token, Client client) {
        limits.guard(LimitedAction.EMAIL_CODE_VERIFY, AttemptLimits.Subject.NOBODY);
        var now = clock.instant();
        var stored = codes.byTokenHash(EmailCode.hashOfToken(token)).filter(c -> c.purpose() == Purpose.VERIFY_EMAIL);
        var account = stored.flatMap(c -> pending.findById(c.subjectId()));
        if (stored.isEmpty() || account.isEmpty()) {
            throw limits.failed(
                    LimitedAction.EMAIL_CODE_VERIFY,
                    AttemptLimits.Subject.NOBODY,
                    InvalidInput.of("token", "expired", AuthMessages.LINK_EXPIRED));
        }
        var c = stored.get();
        if (c.usedAt() != null || !c.expiresAt().isAfter(now) || !codes.use(c.id(), now)) {
            throw InvalidInput.of("token", "expired", AuthMessages.LINK_EXPIRED);
        }
        var created = create(account.get(), "link", now);
        var here = flow.get(FlowStore.SIGN_UP).filter(s -> created.id().equals(s.pendingId()));
        String sessionId = null;
        if (here.isPresent()) {
            flow.remove(FlowStore.SIGN_UP);
            sessionId = signIns.succeeded(created.id(), "registration", false, client);
        }
        return new Confirmed(created, account.get().app(), sessionId);
    }

    private UserAccount create(PendingAccount p, String via, Instant now) {
        if (accounts.emailInUse(p.email())) { // someone confirmed the same address meanwhile (another flow)
            pending.delete(p.id());
            throw new FlowRejected(Reason.NOT_STARTED, AuthMessages.EMAIL_TAKEN);
        }
        var phone = p.phone() != null && !accounts.anyPhone(p.phone()) ? p.phone() : null;
        accounts.createWithEmail(new UserAccounts.EmailAccount(
                p.id(),
                p.firstName(),
                p.lastName(),
                phone,
                p.email(),
                p.locale(),
                p.termsVersion(),
                p.termsAcceptedAt(),
                p.termsLanguage(),
                p.termsEnglishRequestedAt(),
                now));
        passwords.save(p.id(), p.passwordHash(), now);
        // S-157: the form's marketing consents, with the account (CASL proof: wording, time, place, evidence)
        consents.record(p.id(), p.marketingConsents(), "web_signup", p.email(), phone);
        pending.delete(p.id());
        // S-28: in this transaction — the outbox row commits with the account, or neither does. Ids only.
        events.publishEvent(new UserRegistered(newId(), now, p.id()));
        audit.record(
                p.id(),
                "auth.account_created",
                "user",
                p.id(),
                Map.of("app", p.app().code(), "emailConfirmedBy", via));
        log.info(
                "Account created with email + password: user={} app={}",
                p.id(),
                p.app().code());
        return accounts.findById(p.id()).orElseThrow();
    }

    private void sendVerification(PendingAccount account, Instant now) {
        var issued = EmailCode.issue();
        var codeId = newId();
        codes.issue(new EmailCodes.StoredCode(
                codeId,
                Purpose.VERIFY_EMAIL,
                account.id(),
                account.app(),
                issued.codeHash(),
                issued.tokenHash(),
                0,
                now,
                now.plus(props.codeTtl()),
                null));
        emails.verification(
                new AccountEmails.Recipient(
                        account.email(), account.firstName(), account.locale(), account.app(), "verify:" + codeId),
                issued.code(),
                issued.token(),
                props.codeTtl().toMinutes());
    }

    private SignUp current() {
        return flow.get(FlowStore.SIGN_UP)
                .orElseThrow(() -> new FlowRejected(Reason.NOT_STARTED, "Create the account again."));
    }

    private void throttle(Instant sentAt, Instant now) {
        var wait = secondsUntilResend(sentAt, now);
        if (wait > 0) {
            throw new FlowRejected(Reason.THROTTLED, "Wait %d s before sending another email.".formatted(wait), wait);
        }
    }

    private long secondsUntilResend(Instant sentAt, Instant now) {
        var left = sentAt.plus(props.resendAfter()).getEpochSecond() - now.getEpochSecond();
        return Math.max(0, left);
    }

    private static AccountEmails.Recipient recipient(String email, String firstName, String locale, App app) {
        return new AccountEmails.Recipient(email, firstName, locale, app, "exists:" + newId());
    }

    static String localeOf(UserAccount account) {
        return account.locale() == null ? "en-CA" : account.locale();
    }

    /** {@code fr} when the browser asked for French, else {@code en}: the language the consent wording was shown in. */
    static String language() {
        return "fr".equals(LocaleContextHolder.getLocale().getLanguage().toLowerCase(Locale.ROOT)) ? "fr" : "en";
    }

    /** fr-CA when the browser asked for French, else en-CA (as registration does). */
    static String requestLocale() {
        var language = LocaleContextHolder.getLocale().getLanguage().toLowerCase(Locale.ROOT);
        return "fr".equals(language) ? "fr-CA" : "en-CA";
    }

    static String newId() {
        return UlidCreator.getMonotonicUlid().toString();
    }
}
