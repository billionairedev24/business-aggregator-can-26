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
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create account (design 02, "Create account" tab): form → 6-digit phone code → second factor (passkey or
 * authenticator app; mandatory, SMS never primary) → account created. The {@code identity.users} row is only written
 * once the second factor is confirmed.
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultSecretGenerator SECRETS = new DefaultSecretGenerator(32);

    private final UserAccounts accounts;
    private final SecondFactors factors;
    private final SmsSender sms;
    private final FlowStore flow;
    private final PasskeyService passkeys;
    private final SignInLog signIns;
    private final AuthProperties props;
    private final Clock clock;

    /** Input of the first step, already shape-validated (validation-rules.md) by the web adapter. */
    public record Start(String firstName, String lastName, String phone, String email) {}

    /** A new account, signed in with these factors. */
    public record Created(UserAccount account, Factor secondFactor) {}

    /** Authenticator-app set-up: the secret, the otpauth:// URI and the same URI as a PNG QR code (data URI). */
    public record TotpSetup(String secret, String otpauthUri, String qrCode) {}

    /** Step 1: checks that email and phone are free, sends the code by SMS. */
    public PendingRegistration start(Start in) {
        var phone = PhoneNumber.parse(in.phone())
                .orElseThrow(() -> InvalidInput.of("phone", "format", AuthMessages.PHONE_FORMAT));
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
        var registration = new PendingRegistration(
                UlidCreator.getMonotonicUlid().toString(),
                in.firstName().trim(),
                in.lastName().trim(),
                phone,
                email,
                props.termsVersion(),
                sendCode(phone, Channel.SMS),
                false,
                null);
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
        var next = registration.withOtp(sendCode(registration.phone(), channel));
        flow.put(FlowStore.REGISTRATION, next);
        return next;
    }

    /** Step 2: the 6-digit code. */
    public PendingRegistration verifyPhone(String code) {
        var registration = current();
        if (registration.phoneVerified()) {
            return registration;
        }
        return switch (registration.otp().check(code, clock.instant(), props.otpMaxAttempts())) {
            case OtpChallenge.Check.Verified _ -> {
                var verified = registration.withPhoneVerified(true);
                flow.put(FlowStore.REGISTRATION, verified);
                yield verified;
            }
            case OtpChallenge.Check.Wrong(var next) -> {
                flow.put(FlowStore.REGISTRATION, registration.withOtp(next));
                throw InvalidInput.of("code", "mismatch", CODE_WRONG);
            }
            case OtpChallenge.Check.Expired _ -> throw InvalidInput.of("code", "expired", CODE_EXPIRED);
            case OtpChallenge.Check.Locked(var next) -> {
                flow.put(FlowStore.REGISTRATION, registration.withOtp(next));
                throw InvalidInput.of("code", "locked", CODE_LOCKED);
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
                factor.code(),
                registration.termsVersion(),
                now));
        flow.remove(FlowStore.REGISTRATION);
        signIns.succeeded(registration.userId(), "registration", true, client);
        var account = accounts.findById(registration.userId()).orElseThrow();
        return new Created(account, factor);
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

    private OtpChallenge sendCode(PhoneNumber phone, Channel channel) {
        var code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        sms.sendCode(phone, code, channel);
        return OtpChallenge.issue(code, channel, clock.instant(), props.otpTtl());
    }

    /** Locale for new accounts: fr-CA when the browser asked for French, else en-CA. */
    private static String requestLocale() {
        var language = LocaleContextHolder.getLocale().getLanguage().toLowerCase(Locale.ROOT);
        return "fr".equals(language) ? "fr-CA" : "en-CA";
    }
}
