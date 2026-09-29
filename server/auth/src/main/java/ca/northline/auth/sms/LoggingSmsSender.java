package ca.northline.auth.sms;

import ca.northline.auth.application.SmsSender;
import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PhoneNumber;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code northline.sms.provider=local}: writes the code to the log instead of sending it
 * ({@code grep "Verification code" auth.log}). Refused under staging/prod ({@link SmsConfig}).
 */
@Slf4j
class LoggingSmsSender implements SmsSender {

    @Override
    public void sendCode(PhoneNumber to, String code, Channel channel, Locale locale) {
        log.warn("[{}] Verification code for {}: {} (not sent — local SMS fake)", channel, to.display(), code);
    }
}
