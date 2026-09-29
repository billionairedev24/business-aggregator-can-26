package ca.northline.auth.application;

import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PhoneNumber;

/**
 * Outbound port: delivers a one-time code by SMS or voice call. The {@code local}/{@code test} adapter logs it
 * ({@code LoggingSmsSender}); production plugs in the SMS provider.
 */
public interface SmsSender {

    void sendCode(PhoneNumber to, String code, Channel channel);
}
