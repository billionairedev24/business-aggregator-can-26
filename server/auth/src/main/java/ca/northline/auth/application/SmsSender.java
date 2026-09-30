package ca.northline.auth.application;

import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PhoneNumber;
import java.util.Locale;

/**
 * Outbound port: delivers a one-time code by SMS or voice call, worded in the person's language (French for a
 * {@code fr} locale, English otherwise). Adapters in {@code ca.northline.auth.sms}, chosen by
 * {@code northline.sms.provider}: {@code local} logs the code, {@code twilio} and {@code aws} send it.
 */
public interface SmsSender {

    /**
     * Sends {@code code} to {@code to}; returns once the provider accepted it.
     *
     * @throws SmsDeliveryFailed when the provider refused the number or could not be reached
     */
    void sendCode(PhoneNumber to, String code, Channel channel, Locale locale);
}
