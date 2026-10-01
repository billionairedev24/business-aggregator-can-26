package ca.northline.sms;

import ca.northline.platform.SmsProperties;
import ca.northline.sms.aws.AwsSmsTransport;
import ca.northline.sms.twilio.TwilioApi;
import ca.northline.sms.twilio.TwilioSmsTransport;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
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
 * Builds the {@link SmsTransport} of {@code northline.sms.provider} ({@code SMS_PROVIDER}) — the rules S-8 set for
 * northline-auth, now shared: {@code local} (the log; refused under staging/prod), {@code twilio}, {@code aws};
 * {@code azure} is reserved. A provider with missing settings fails naming every missing variable.
 */
@Slf4j
public final class SmsTransports {

    public static final String TWILIO_API = "https://api.twilio.com";

    private SmsTransports() {}

    public static SmsTransport of(SmsProperties sms, Environment environment) {
        return switch (sms.provider()) {
            case LOCAL -> local(environment);
            case TWILIO -> twilio(sms);
            case AWS -> aws(awsClient(sms), sms);
            case AZURE -> throw azureNotImplemented();
        };
    }

    public static SmsTransport local(Environment environment) {
        refuseLocal(environment);
        var reveal = environment.matchesProfiles("local | test");
        if (!reveal) {
            log.warn("SMS_PROVIDER=local: text messages are neither sent nor logged (S-112)");
        }
        return new LoggingSmsTransport(reveal);
    }

    /** {@code local} is a developer fake: staging and prod must send for real. */
    public static void refuseLocal(Environment environment) {
        if (environment.matchesProfiles("staging | prod")) {
            throw new IllegalStateException("SMS_PROVIDER=local is not allowed under staging/prod: set SMS_PROVIDER to"
                    + " twilio or aws (docs/runbooks/README.md § SMS and voice codes)");
        }
    }

    public static TwilioSmsTransport twilio(SmsProperties sms) {
        var problems = new ArrayList<String>();
        var account = require(sms.accountId(), "SMS_ACCOUNT_ID (Twilio account SID AC…)", problems);
        var token = require(sms.authToken(), "SMS_AUTH_TOKEN (Twilio auth token)", problems);
        var from = require(sms.from(), "SMS_FROM (+1… number or Messaging Service MG…)", problems);
        var voiceFrom = StringUtils.hasText(sms.voiceFrom()) ? sms.voiceFrom().strip() : from;
        if (!from.isEmpty() && !from.startsWith("MG") && !PhoneNumbers.isE164(from)) {
            problems.add("SMS_FROM must be an E.164 number (+1…) or a Messaging Service SID (MG…)");
        }
        if (!voiceFrom.isEmpty() && !PhoneNumbers.isE164(voiceFrom)) {
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
        return new TwilioSmsTransport(api, account, from, voiceFrom);
    }

    /** No SDK retries: a retried send can deliver twice (same rule as Twilio). */
    public static PinpointSmsVoiceV2Client awsClient(SmsProperties sms) {
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

    public static AwsSmsTransport aws(PinpointSmsVoiceV2Client client, SmsProperties sms) {
        var problems = new ArrayList<String>();
        var from = require(sms.from(), "SMS_FROM (origination number, its id/ARN, or a pool)", problems);
        fail("aws", problems);
        var voiceFrom = StringUtils.hasText(sms.voiceFrom()) ? sms.voiceFrom().strip() : from;
        log.info("SMS: provider=aws from={} voice-from={} region={}", from, voiceFrom, sms.region());
        return new AwsSmsTransport(client, from, voiceFrom);
    }

    private static IllegalStateException azureNotImplemented() {
        return new IllegalStateException("SMS_PROVIDER=azure (Azure Communication Services) is not implemented yet: use"
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
