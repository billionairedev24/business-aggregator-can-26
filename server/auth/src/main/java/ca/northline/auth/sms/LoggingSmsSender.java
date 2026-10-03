package ca.northline.auth.sms;

import ca.northline.auth.application.SmsSender;
import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PhoneNumber;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * {@code northline.sms.provider=local}: writes the code to the log instead of sending it
 * ({@code grep "Verification code" auth.log}). Refused under staging/prod ({@link SmsConfig}). S-112: the code and the
 * number are written only on a developer's machine ({@code local} / {@code test} profiles); anywhere else (dev) the
 * line says the code was withheld, so no environment that ships logs ever holds a working code. S-117: under {@code local}
 * the code is also kept in {@link DevOutbox} for the end-to-end suite.
 */
@Slf4j
class LoggingSmsSender implements SmsSender {

    private final boolean revealCodes;
    private final @Nullable DevOutbox outbox;

    LoggingSmsSender(boolean revealCodes, @Nullable DevOutbox outbox) {
        this.revealCodes = revealCodes;
        this.outbox = revealCodes ? outbox : null;
    }

    @Override
    public void sendCode(PhoneNumber to, String code, Channel channel, Locale locale) {
        if (revealCodes) {
            log.warn("[{}] Verification code for {}: {} (not sent — local SMS fake)", channel, to.display(), code);
            if (outbox != null) {
                outbox.record(to.e164(), channel.name().toLowerCase(Locale.ROOT), code); // S-117: local only
            }
        } else {
            log.warn(
                    "[{}] Verification code withheld (not sent — SMS_PROVIDER=local outside a local run; set"
                            + " SMS_PROVIDER to twilio or aws to deliver codes)",
                    channel);
        }
    }
}
