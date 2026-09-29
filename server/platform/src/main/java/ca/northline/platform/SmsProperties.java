package ca.northline.platform;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.sms.*} — SMS and voice codes. Only {@code local} exists today ({@code LoggingSmsSender} under the
 * {@code local}/{@code test} profiles, {@code UnconfiguredSmsSender} elsewhere); the Twilio adapter comes with backlog
 * story S-8.
 *
 * @param provider {@code local | twilio | sns | azure} ({@code SMS_PROVIDER})
 * @param from sender number or messaging service id ({@code SMS_FROM})
 * @param accountId provider account (Twilio account SID) ({@code SMS_ACCOUNT_ID})
 * @param authToken provider credential ({@code SMS_AUTH_TOKEN})
 */
@ConfigurationProperties("northline.sms")
public record SmsProperties(
        @DefaultValue("local") Provider provider,
        @Nullable String from,
        @Nullable String accountId,
        @Nullable String authToken) {

    /** Which adapter sends codes. */
    public enum Provider {
        LOCAL,
        TWILIO,
        SNS,
        AZURE
    }
}
