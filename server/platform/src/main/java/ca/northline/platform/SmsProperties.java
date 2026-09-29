package ca.northline.platform;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.sms.*} — SMS and voice verification codes (S-8, northline-auth). {@code staging}/{@code prod} refuse
 * {@code local}. See docs/runbooks/README.md § SMS and voice codes.
 *
 * @param provider {@code local} (codes written to the log) | {@code twilio} (Programmable Messaging + Voice) |
 *     {@code aws} (AWS End User Messaging SMS and voice) | {@code azure} (not implemented yet) ({@code SMS_PROVIDER})
 * @param from sender: an E.164 number (Canadian long code or verified toll-free), or for Twilio a Messaging Service
 *     SID {@code MG…}, for AWS a phone number id/ARN or pool ({@code SMS_FROM})
 * @param voiceFrom caller id for voice codes when {@code from} can't place calls (a Messaging Service, a pool);
 *     empty = {@code from} ({@code SMS_VOICE_FROM})
 * @param accountId Twilio account SID {@code AC…} ({@code SMS_ACCOUNT_ID}; unused by AWS: workload identity)
 * @param authToken Twilio auth token ({@code SMS_AUTH_TOKEN})
 * @param region AWS region, e.g. {@code ca-central-1}; empty = the SDK default chain ({@code SMS_REGION})
 * @param endpoint API base override: a mock in tests, LocalStack, a VPC endpoint ({@code SMS_ENDPOINT})
 */
@ConfigurationProperties("northline.sms")
public record SmsProperties(
        @DefaultValue("local") Provider provider,
        @Nullable String from,
        @Nullable String voiceFrom,
        @Nullable String accountId,
        @Nullable String authToken,
        @Nullable String region,
        @Nullable String endpoint) {

    /** Which adapter sends codes. */
    public enum Provider {
        LOCAL,
        TWILIO,
        AWS,
        AZURE
    }
}
