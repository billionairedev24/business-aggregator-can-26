package ca.northline.auth.sms;

import ca.northline.auth.application.SmsSender;
import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PhoneNumber;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Placeholder outside {@code local}/{@code test}: no SMS provider has been chosen yet (see DECISIONS.md), so
 * registration fails loudly instead of silently dropping codes.
 */
@Component
@Profile("!local & !test")
class UnconfiguredSmsSender implements SmsSender {

    @Override
    public void sendCode(PhoneNumber to, String code, Channel channel) {
        throw new IllegalStateException("No SMS provider configured for northline-auth");
    }
}
