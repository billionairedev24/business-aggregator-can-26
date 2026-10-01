package ca.northline.auth.sms;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.auth.application.SmsSender;
import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PhoneNumber;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** {@code northline.sms.provider} picks the adapter; missing settings and {@code local} under staging/prod stop start-up. */
class SmsConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SmsConfig.class);

    private static final String[] TWILIO = {
        "northline.sms.provider=twilio",
        "northline.sms.account-id=ACtest-account-sid",
        "northline.sms.auth-token=secret",
        "northline.sms.from=+15875550100"
    };

    @Test
    void local_isTheDefault_andLogs() {
        runner.run(ctx -> assertThat(ctx).getBean(SmsSender.class).isInstanceOf(LoggingSmsSender.class));
        runner.withPropertyValues("spring.profiles.active=dev")
                .run(ctx -> assertThat(ctx).getBean(SmsSender.class).isInstanceOf(LoggingSmsSender.class));
    }

    /**
     * S-20 accepted that the local fake logs codes on a developer's machine; S-112 makes sure that can't happen
     * anywhere else: under dev (which may keep the fake) neither the code nor the number reaches the log. Checked on
     * the formatted message itself, whatever the console format.
     */
    @Test
    void local_logsTheCodeOnlyOnADevelopersMachine() {
        var to = PhoneNumber.parse("+1 587 555 0101").orElseThrow();
        var logged = capture(LoggingSmsSender.class);
        runner.withPropertyValues("spring.profiles.active=local")
                .run(ctx -> ctx.getBean(SmsSender.class).sendCode(to, "482913", Channel.SMS, Locale.CANADA));
        assertThat(messages(logged)).anyMatch(m -> m.contains("Verification code for +1 587 555 0101: 482913"));

        logged.list.clear();
        runner.withPropertyValues("spring.profiles.active=dev")
                .run(ctx -> ctx.getBean(SmsSender.class).sendCode(to, "731264", Channel.VOICE, Locale.CANADA_FRENCH));
        assertThat(messages(logged))
                .noneMatch(m -> m.contains("731264") || m.contains("555"))
                .anyMatch(m -> m.contains("[VOICE] Verification code withheld"));
    }

    static ListAppender<ILoggingEvent> capture(Class<?> type) {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(type)).addAppender(appender);
        return appender;
    }

    static List<String> messages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void local_isRefusedUnderStagingAndProd() {
        for (var profile : new String[] {"staging", "prod"}) {
            runner.withPropertyValues("spring.profiles.active=" + profile, "northline.sms.provider=local")
                    .run(ctx -> assertThat(ctx)
                            .hasFailed()
                            .getFailure()
                            .rootCause()
                            .hasMessageContaining("SMS_PROVIDER=local is not allowed under staging/prod"));
        }
    }

    @Test
    void twilio_withCredentials() {
        runner.withPropertyValues(TWILIO)
                .withPropertyValues("spring.profiles.active=prod")
                .run(ctx -> assertThat(ctx).getBean(SmsSender.class).isInstanceOf(TwilioSmsSender.class));
    }

    @Test
    void twilio_withoutCredentials_failsFast_namingTheVariables() {
        runner.withPropertyValues("northline.sms.provider=twilio", "spring.profiles.active=prod")
                .run(ctx -> assertThat(ctx)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("SMS_PROVIDER=twilio needs: SMS_ACCOUNT_ID")
                        .hasMessageContaining("SMS_AUTH_TOKEN")
                        .hasMessageContaining("SMS_FROM"));
    }

    @Test
    void twilio_messagingServiceNeedsAVoiceNumber() {
        runner.withPropertyValues(TWILIO)
                .withPropertyValues("northline.sms.from=MG0123456789abcdef0123456789abcdef")
                .run(ctx ->
                        assertThat(ctx).hasFailed().getFailure().rootCause().hasMessageContaining("SMS_VOICE_FROM"));
        runner.withPropertyValues(TWILIO)
                .withPropertyValues(
                        "northline.sms.from=MG0123456789abcdef0123456789abcdef",
                        "northline.sms.voice-from=+15875550101")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void aws_needsAnOriginationIdentity() {
        runner.withPropertyValues(
                        "northline.sms.provider=aws",
                        "northline.sms.region=ca-central-1",
                        "northline.sms.from=+15875550100")
                .run(ctx -> assertThat(ctx).getBean(SmsSender.class).isInstanceOf(AwsSmsSender.class));
        runner.withPropertyValues("northline.sms.provider=aws", "northline.sms.region=ca-central-1")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause().hasMessageContaining("SMS_FROM"));
    }

    @Test
    void azure_isReservedButNotImplemented() {
        runner.withPropertyValues("northline.sms.provider=azure")
                .run(ctx -> assertThat(ctx)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("not implemented yet"));
    }
}
