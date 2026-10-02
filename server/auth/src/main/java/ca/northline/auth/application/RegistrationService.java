package ca.northline.auth.application;

import static ca.northline.auth.domain.AuthMessages.CODE_EXPIRED;
import static ca.northline.auth.domain.AuthMessages.CODE_LOCKED;
import static ca.northline.auth.domain.AuthMessages.CODE_WRONG;

import ca.northline.auth.application.FlowRejected.Reason;
import ca.northline.auth.application.SignInLog.Client;
import ca.northline.auth.domain.AuthMessages;
import ca.northline.auth.domain.Factor;
import ca.northline.auth.domain.OtpChallenge;
import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PendingRegistration;
import ca.northline.auth.domain.PhoneNumber;
import ca.northline.auth.domain.Totp;
import com.github.f4b6a3.ulid.UlidCreator;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.util.Utils;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create account (design 02, "Create account" tab): form → 6-digit phone code → second factor (passkey or
 * authenticator app; SMS never primary) → account created. The {@code identity.users} row is only written once the
 * account is complete, together with the {@link UserRegistered} event (S-28).
 *
 * <p>S-62 (design 06, consumer): a consumer may finish without a second factor ("SMS code · Backup only" →
 * {@link #completeWithoutSecondFactor}) — the account's sign-ins then carry no {@code acr=mfa}, which business and staff
 * endpoints require (validation-rules.md: the second factor is mandatory for business accounts, not for consumers).
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private static final DefaultSecretGenerator SECRETS = new DefaultSecretGenerator(32);

    private final UserAccounts accounts;
    private final SecondFactors factors;
    private final PhoneCodes codes;
    private final FlowStore flow;
    private final PasskeyService passkeys;
    private final SignInLog signIns;
    private final AttemptLimits limits;
    private final FederatedLinking federation;
    private final ApplicationEventPublisher events;
    private final AuthProperties props;
    private final Clock clock;

    /**
     * Input of the first step, already shape-validated (validation-rules.md) by the web adapter.
     *
     * @param termsLanguage the language the Terms were shown in ({@code en} | {@code fr}), null when not said
     * @param termsEnglishRequested an express request for the English Terms where they come in French first (S-116)
     */
    public record Start(
            String firstName,
            String lastName,
            String phone,
            String email,
            @Nullable String termsLanguage,
            boolean termsEnglishRequested) {

        public Start(String firstName, String lastName, String phone, String email) {
            this(firstName, lastName, phone, email, null, false);
        }
    }

    /** A new account, signed in with these factors; {@code sessionId} is its first session ({@code identity.sessions}). */
    public record Created(UserAccount account, Factor secondFactor, String sessionId) {}

    /** Authenticator-app set-up: the secret, the otpauth:// URI and the same URI as a PNG QR code (data URI). */
    public record TotpSetup(String secret, String otpauthUri, String qrCode) {}

    /** Step 1: checks that email and phone are free, sends the code by SMS. */
    public PendingRegistration start(Start in) {
        var phone = PhoneNumber.parse(in.phone())
                .orElseThrow(() -> InvalidInput.of("phone", "format", AuthMessages.PHONE_FORMAT));
        // Every submission counts (also the ones answered "already in use"): codes cost money and the answer is a hint.
        limits.consume(LimitedAction.OTP_SEND, phoneSubject(phone));
        var email = in.email().trim();
        var taken = new ArrayList<InvalidInput.Violation>();
        if (accounts.emailInUse(email)) {
            taken.add(new InvalidInput.Violation("email", "unique", AuthMessages.EMAIL_TAKEN));
        }
        if (accounts.phoneInUse(phone.e164())) {
            taken.add(new InvalidInput.Violation("phone", "unique", AuthMessages.PHONE_TAKEN));
        }
        if (!taken.isEmpty()) {
            throw new InvalidInput(taken);
        }
        // "Back" and submitting the same form again must not become a way around the resend cool-down.
        var open = flow.get(FlowStore.REGISTRATION)
                .filter(r -> r.phone().equals(phone) && !r.phoneVerified())
                .filter(r -> r.otp().secondsUntilResend(clock.instant(), props.otpResendAfter()) > 0);
        if (open.isPresent()) {
            var same = open.get()
                    .withFirstName(in.firstName().trim())
                    .withLastName(in.lastName().trim())
                    .withEmail(email)
                    .withTermsLanguage(termsLanguage(in))
                    .withTermsEnglishRequested(in.termsEnglishRequested());
            flow.put(FlowStore.REGISTRATION, same);
            return same;
        }
        var registration = new PendingRegistration(
                UlidCreator.getMonotonicUlid().toString(),
                in.firstName().trim(),
                in.lastName().trim(),
                phone,
                email,
                props.termsVersion(),
                codes.send(phone, Channel.SMS, true),
                false,
                null,
                termsLanguage(in),
                in.termsEnglishRequested());
        flow.remove(FlowStore.PASSKEY_CREATION);
        flow.put(FlowStore.REGISTRATION, registration);
        return registration;
    }

    /** "Resend" (SMS) after the 45 s cool-down, or "Call me instead" (voice, once per SMS code). */
    public PendingRegistration resend(Channel channel) {
        var registration = current();
        var otp = registration.otp();
        long wait = otp.secondsUntilResend(clock.instant(), props.otpResendAfter());
        boolean voiceFallback = channel == Channel.VOICE && otp.channel() == Channel.SMS;
        if (wait > 0 && !voiceFallback) {
            throw new FlowRejected(Reason.THROTTLED, "Wait %d s before sending another code.".formatted(wait), wait);
        }
        limits.consume(LimitedAction.OTP_SEND, phoneSubject(registration.phone()));
        var next = registration.withOtp(codes.send(registration.phone(), channel, false));
        flow.put(FlowStore.REGISTRATION, next);
        return next;
    }

    /** Step 2: the 6-digit code. */
    public PendingRegistration verifyPhone(String code) {
        var registration = current();
        if (registration.phoneVerified()) {
            return registration;
        }
        var who = phoneSubject(registration.phone());
        limits.guard(LimitedAction.OTP_VERIFY, who);
        return switch (registration.otp().check(code, clock.instant(), props.otpMaxAttempts())) {
            case OtpChallenge.Check.Verified _ -> {
                var verified = registration.withPhoneVerified(true);
                flow.put(FlowStore.REGISTRATION, verified);
                limits.succeeded(LimitedAction.OTP_VERIFY, who);
                yield verified;
            }
            case OtpChallenge.Check.Wrong(var next) -> {
                flow.put(FlowStore.REGISTRATION, registration.withOtp(next));
                throw limits.failed(LimitedAction.OTP_VERIFY, who, InvalidInput.of("code", "mismatch", CODE_WRONG));
            }
            case OtpChallenge.Check.Expired _ -> throw InvalidInput.of("code", "expired", CODE_EXPIRED);
            case OtpChallenge.Check.Locked(var next) -> {
                flow.put(FlowStore.REGISTRATION, registration.withOtp(next));
                throw limits.failed(LimitedAction.OTP_VERIFY, who, InvalidInput.of("code", "locked", CODE_LOCKED));
            }
        };
    }

    /** Step 3a: passkey options for {@code navigator.credentials.create()}. */
    public String passkeyOptions() {
        var registration = verifiedRegistration();
        var options = passkeys.creationOptions(registration.userId(), registration.email(), registration.fullName());
        flow.put(FlowStore.PASSKEY_CREATION, options);
        return passkeys.toJson(options);
    }

    /** Step 3a: the new passkey — account created. */
    @Transactional
    public Created completeWithPasskey(String credentialJson, String label, Client client) {
        var registration = verifiedRegistration();
        var options = flow.get(FlowStore.PASSKEY_CREATION)
                .orElseThrow(() -> new FlowRejected(Reason.NOT_STARTED, "Start the passkey set-up again."));
        passkeys.register(options, credentialJson, label);
        var created = create(registration, Factor.PASSKEY, client);
        flow.remove(FlowStore.PASSKEY_CREATION);
        return created;
    }

    /** Step 3b: a fresh authenticator secret (kept until confirmed). */
    public TotpSetup totpSetup() {
        var registration = verifiedRegistration();
        var secret = SECRETS.generate();
        flow.put(FlowStore.REGISTRATION, registration.withTotpSecret(secret));
        var data = new QrData.Builder()
                .label(registration.email())
                .secret(secret)
                .issuer(props.totpIssuer())
                .digits(Totp.DIGITS)
                .period(Totp.PERIOD_SECONDS)
                .build();
        try {
            var png = new ZxingPngQrGenerator();
            return new TotpSetup(
                    secret, data.getUri(), Utils.getDataUriForImage(png.generate(data), png.getImageMimeType()));
        } catch (QrGenerationException e) {
            throw new IllegalStateException("QR generation failed", e);
        }
    }

    /** Step 3b: the first code from the authenticator app — account created. */
    @Transactional
    public Created completeWithTotp(String code, Client client) {
        var registration = verifiedRegistration();
        var secret = registration.totpSecret();
        if (secret == null) {
            throw new FlowRejected(Reason.NOT_STARTED, "Scan the QR code first.");
        }
        var step = Totp.verify(secret, code, clock.instant(), Long.MIN_VALUE)
                .orElseThrow(() -> InvalidInput.of("code", "mismatch", CODE_WRONG));
        var created = create(registration, Factor.TOTP, client);
        factors.saveTotp(registration.userId(), secret, clock.instant());
        factors.markTotpUsed(registration.userId(), step);
        return created;
    }

    /** S-62, step 3c (consumer): no second factor — the verified phone is the account's only factor for now. */
    @Transactional
    public Created completeWithoutSecondFactor(Client client) {
        return create(verifiedRegistration(), Factor.PHONE_OTP, client);
    }

    private Created create(PendingRegistration registration, Factor factor, Client client) {
        // Re-check: someone may have registered the same email/phone while this flow was open.
        if (accounts.emailInUse(registration.email())
                || accounts.phoneInUse(registration.phone().e164())) {
            flow.remove(FlowStore.REGISTRATION);
            throw new FlowRejected(Reason.NOT_STARTED, AuthMessages.EMAIL_TAKEN);
        }
        var now = clock.instant();
        accounts.create(new UserAccounts.NewAccount(
                registration.userId(),
                registration.firstName(),
                registration.lastName(),
                registration.phone().e164(),
                registration.email(),
                requestLocale(),
                factor == Factor.PHONE_OTP ? "sms" : factor.code(), // identity.users.mfa_primary: passkey | totp | sms
                registration.termsVersion(),
                now,
                registration.termsLanguage(),
                registration.termsEnglishRequested() ? now : null));
        // S-28: in this transaction — the outbox row commits with the account, or neither does.
        events.publishEvent(new UserRegistered(UlidCreator.getMonotonicUlid().toString(), now, registration.userId()));
        flow.remove(FlowStore.REGISTRATION);
        var sessionId = signIns.succeeded(registration.userId(), "registration", factor.isSecondFactor(), client);
        federation.complete(registration.userId(), true); // S-18: created after "Continue with Google/Apple"
        var account = accounts.findById(registration.userId()).orElseThrow();
        return new Created(account, factor, sessionId);
    }

    private PendingRegistration current() {
        return flow.get(FlowStore.REGISTRATION)
                .orElseThrow(() -> new FlowRejected(Reason.NOT_STARTED, "Start the registration again."));
    }

    private PendingRegistration verifiedRegistration() {
        var registration = current();
        if (!registration.phoneVerified()) {
            throw new FlowRejected(Reason.NOT_STARTED, "Verify your mobile number first.");
        }
        return registration;
    }

    private static AttemptLimits.Subject phoneSubject(PhoneNumber phone) {
        return AttemptLimits.Subject.identifier(phone.e164(), null);
    }

    /** An express request for the English Terms means they were shown in English. */
    private static @Nullable String termsLanguage(Start in) {
        return in.termsEnglishRequested() ? "en" : in.termsLanguage();
    }

    /** Locale for new accounts: fr-CA when the browser asked for French, else en-CA. */
    private static String requestLocale() {
        var language = LocaleContextHolder.getLocale().getLanguage().toLowerCase(Locale.ROOT);
        return "fr".equals(language) ? "fr-CA" : "en-CA";
    }
}
