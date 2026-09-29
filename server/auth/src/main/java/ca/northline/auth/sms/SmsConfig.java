package ca.northline.auth.sms;

import ca.northline.auth.application.SmsSender;
import ca.northline.platform.SmsProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;

/**
 * Wires {@link SmsSender} from {@code northline.sms.provider} ({@code SMS_PROVIDER}): {@code local} (default: codes in
 * the log), {@code twilio}, {@code aws}. A provider with missing settings stops start-up naming the variables;
 * {@code staging}/{@code prod} refuse {@code local} (their required-env also lists {@code SMS_PROVIDER},
 * {@code SMS_FROM}). {@code azure} is reserved and not implemented.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SmsProperties.class)
public class SmsConfig {

    static final String PROVIDER = "northline.sms.provider";
    static final String TWILIO_API = "https://api.twilio.com";
    private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{6,14}");

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
        var problems = new ArrayList<String>();
        var account = require(sms.accountId(), "SMS_ACCOUNT_ID (Twilio account SID AC…)", problems);
        var token = require(sms.authToken(), "SMS_AUTH_TOKEN (Twilio auth token)", problems);
        var from = require(sms.from(), "SMS_FROM (+1… number or Messaging Service MG…)", problems);
        var voiceFrom = StringUtils.hasText(sms.voiceFrom()) ? sms.voiceFrom().strip() : from;
        if (!from.isEmpty() && !from.startsWith("MG") && !E164.matcher(from).matches()) {
            problems.add("SMS_FROM must be an E.164 number (+1…) or a Messaging Service SID (MG…)");
        }
        if (!voiceFrom.isEmpty() && !E164.matcher(voiceFrom).matches()) {
            problems.add("SMS_VOICE_FROM must be an E.164 voice-capable number (a Messaging Service can't call)");
        }
        fail("twilio", problems);
        var requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requests.setReadTimeout(Duration.ofSeconds(10));
        var rest = RestClient.builder()
                .baseUrl(StringUtils.hasText(sms.endpoint()) ? sms.endpoint().strip() : TWILIO_API)
                .defaultHeaders(h -> h.setBasicAuth(account, token))
                .requestFactory(requests)
                .build();
        var api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest))
                .build()
                .createClient(TwilioApi.class);
        log.info("SMS: provider=twilio from={} voice-from={}", from, voiceFrom);
        return new TwilioSmsSender(api, account, from, voiceFrom);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = PROVIDER, havingValue = "aws")
    static class Aws {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        PinpointSmsVoiceV2Client pinpointSmsVoiceV2Client(SmsProperties sms) {
            // No SDK retries: a retried send can deliver twice, and the person can resend (same rule as Twilio).
            var builder = PinpointSmsVoiceV2Client.builder()
                    .overrideConfiguration(
                            o -> o.retryStrategy(AwsRetryStrategy.doNotRetry()).apiCallTimeout(Duration.ofSeconds(10)));
            if (StringUtils.hasText(sms.region())) {
                builder.region(Region.of(sms.region().strip()));
            }
            if (StringUtils.hasText(sms.endpoint())) {
                builder.endpointOverride(URI.create(sms.endpoint().strip()));
            }
            return builder.build();
        }

        @Bean
        SmsSender awsSmsSender(PinpointSmsVoiceV2Client client, SmsProperties sms) {
            var problems = new ArrayList<String>();
            var from = require(sms.from(), "SMS_FROM (origination number, its id/ARN, or a pool)", problems);
            fail("aws", problems);
            var voiceFrom =
                    StringUtils.hasText(sms.voiceFrom()) ? sms.voiceFrom().strip() : from;
            log.info("SMS: provider=aws from={} voice-from={} region={}", from, voiceFrom, sms.region());
            return new AwsSmsSender(client, from, voiceFrom);
        }
    }

    @Bean
    @ConditionalOnProperty(name = PROVIDER, havingValue = "azure")
    SmsSender azureSmsSender() {
        throw new IllegalStateException("SMS_PROVIDER=azure (Azure Communication Services) is not implemented yet: use"
                + " twilio or aws (docs/runbooks/README.md § SMS and voice codes)");
    }

    private static String require(@Nullable String value, String what, List<String> problems) {
        if (!StringUtils.hasText(value)) {
            problems.add(what);
            return "";
        }
        return value.strip();
    }

    private static void fail(String provider, List<String> problems) {
        if (!problems.isEmpty()) {
            throw new IllegalStateException("SMS_PROVIDER=" + provider + " needs: " + String.join("; ", problems)
                    + " (docs/runbooks/README.md § SMS" + " and voice codes)");
        }
    }
}
