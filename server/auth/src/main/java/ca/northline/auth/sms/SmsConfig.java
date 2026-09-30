package ca.northline.auth.sms;

import ca.northline.auth.application.SmsSender;
import ca.northline.platform.SmsProperties;
import ca.northline.sms.SmsTransports;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;

/**
 * Wires {@link SmsSender} from {@code northline.sms.provider} ({@code SMS_PROVIDER}): {@code local} (default: codes in
 * the log), {@code twilio}, {@code aws} — the provider adapters and their settings checks live in the shared SMS library
 * ({@code server/sms}, {@link SmsTransports}; S-27), wrapped here with auth's code texts. A provider with missing settings stops start-up naming the variables;
 * {@code staging}/{@code prod} refuse {@code local} (their required-env also lists {@code SMS_PROVIDER},
 * {@code SMS_FROM}). {@code azure} is reserved and not implemented.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SmsProperties.class)
public class SmsConfig {

    static final String PROVIDER = "northline.sms.provider";

    @Bean
    @ConditionalOnProperty(name = PROVIDER, havingValue = "local", matchIfMissing = true)
    SmsSender loggingSmsSender(Environment environment) {
        if (environment.matchesProfiles("staging | prod")) {
            throw new IllegalStateException("SMS_PROVIDER=local is not allowed under staging/prod: set SMS_PROVIDER to"
                    + " twilio or aws (docs/runbooks/README.md § SMS and voice codes)");
        }
        if (environment.matchesProfiles("dev")) {
            log.warn("SMS_PROVIDER=local: verification codes are written to the log, not sent");
        }
        return new LoggingSmsSender();
    }

    @Bean
    @ConditionalOnProperty(name = PROVIDER, havingValue = "twilio")
    SmsSender twilioSmsSender(SmsProperties sms) {
        return new TwilioSmsSender(SmsTransports.twilio(sms));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = PROVIDER, havingValue = "aws")
    static class Aws {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        PinpointSmsVoiceV2Client pinpointSmsVoiceV2Client(SmsProperties sms) {
            // No SDK retries: a retried send can deliver twice, and the person can resend (same rule as Twilio).
            return SmsTransports.awsClient(sms);
        }

        @Bean
        SmsSender awsSmsSender(PinpointSmsVoiceV2Client client, SmsProperties sms) {
            return new AwsSmsSender(SmsTransports.aws(client, sms));
        }
    }

    @Bean
    @ConditionalOnProperty(name = PROVIDER, havingValue = "azure")
    SmsSender azureSmsSender() {
        throw new IllegalStateException("SMS_PROVIDER=azure (Azure Communication Services) is not implemented yet: use"
                + " twilio or aws (docs/runbooks/README.md § SMS and voice codes)");
    }
}
