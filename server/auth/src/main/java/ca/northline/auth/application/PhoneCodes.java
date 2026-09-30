package ca.northline.auth.application;

import ca.northline.auth.application.FlowRejected.Reason;
import ca.northline.auth.domain.AuthMessages;
import ca.northline.auth.domain.OtpChallenge;
import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PhoneNumber;
import java.security.SecureRandom;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Sends 6-digit phone codes (registration, S-62 sign-in by code) in the language of the request and returns the
 * challenge to keep (only its hash). Moved out of {@link RegistrationService} in S-62.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PhoneCodes {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SmsSender sms;
    private final AuthProperties props;
    private final Clock clock;

    /**
     * A number the provider refuses on the form step is a field error on the mobile ("Enter a valid Canadian
     * mobile…"); any other failure is {@code 503 code_not_sent}. Nothing is stored for a code that wasn't sent.
     */
    OtpChallenge send(PhoneNumber phone, Channel channel, boolean formStep) {
        var code = newCode();
        try {
            sms.sendCode(phone, code, channel, LocaleContextHolder.getLocale());
        } catch (SmsDeliveryFailed e) {
            log.warn("{} code to {} not sent ({}): {}", channel, phone.masked(), e.getKind(), e.getMessage());
            if (formStep && e.getKind() == SmsDeliveryFailed.Kind.UNDELIVERABLE_NUMBER) {
                throw InvalidInput.of("phone", "format", AuthMessages.PHONE_FORMAT);
            }
            var message = formStep
                    ? AuthMessages.CODE_NOT_SENT_FORM
                    : channel == Channel.VOICE ? AuthMessages.CALL_NOT_PLACED : AuthMessages.CODE_NOT_SENT;
            throw new FlowRejected(Reason.CODE_NOT_SENT, message);
        }
        return OtpChallenge.issue(code, channel, clock.instant(), props.otpTtl());
    }

    /**
     * A challenge nobody received (S-62: the sign-in asked for a code for an unknown account): the flow goes on exactly
     * as for a real one — same answers, cool-down and failures — without sending anything or revealing that no account
     * matched.
     */
    OtpChallenge unsent(Channel channel) {
        return OtpChallenge.issue(newCode(), channel, clock.instant(), props.otpTtl());
    }

    private static String newCode() {
        return "%06d".formatted(RANDOM.nextInt(1_000_000));
    }
}
