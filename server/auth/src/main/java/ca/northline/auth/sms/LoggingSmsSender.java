package ca.northline.auth.sms;

import ca.northline.auth.application.SmsSender;
import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PhoneNumber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local/test SMS and voice adapter: writes the code to the log instead of sending it
 * ({@code grep "Verification code" auth.log}).
 */
@Slf4j
@Component
@Profile({"local", "test"})
class LoggingSmsSender implements SmsSender {

    @Override
    public void sendCode(PhoneNumber to, String code, Channel channel) {
        log.warn("[{}] Verification code for {}: {} (not sent — local SMS fake)", channel, to.display(), code);
    }
}
