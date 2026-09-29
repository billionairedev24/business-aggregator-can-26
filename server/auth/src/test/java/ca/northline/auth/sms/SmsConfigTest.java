package ca.northline.auth.sms;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.auth.application.SmsSender;
import org.junit.jupiter.api.Test;
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
