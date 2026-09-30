package ca.northline.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.sms.aws.AwsSmsTransport;
import ca.northline.sms.twilio.TwilioSmsTransport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** {@code northline.sms.provider} picks one transport; the S-8 rules (missing settings, `local` in prod) hold. */
class SmsTransportsTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SmsTransportConfiguration.class);

    @Test
    void localIsTheDefault_andRefusedUnderStagingAndProd() {
        runner.run(ctx -> assertThat(ctx).getBean(SmsTransport.class).isInstanceOf(LoggingSmsTransport.class));
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(ctx -> assertThat(ctx)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("SMS_PROVIDER=local is not allowed under staging/prod"));
    }

    @Test
    void twilioAndAws_areBuiltFromTheirSettings() {
        runner.withPropertyValues(
                        "northline.sms.provider=twilio",
                        "northline.sms.account-id=ACtest-account-sid",
                        "northline.sms.auth-token=test-token-not-real",
                        "northline.sms.from=+15875550100")
                .run(ctx -> assertThat(ctx).getBean(SmsTransport.class).isInstanceOf(TwilioSmsTransport.class));
        runner.withPropertyValues(
                        "northline.sms.provider=aws",
                        "northline.sms.region=ca-central-1",
                        "northline.sms.from=+15875550100")
                .run(ctx -> assertThat(ctx).getBean(SmsTransport.class).isInstanceOf(AwsSmsTransport.class));
    }

    @Test
    void missingSettingsAreAllNamed() {
        runner.withPropertyValues("northline.sms.provider=twilio")
                .run(ctx -> assertThat(ctx)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("SMS_ACCOUNT_ID")
                        .hasMessageContaining("SMS_AUTH_TOKEN")
                        .hasMessageContaining("SMS_FROM"));
        assertThatThrownBy(SmsTransports::azure).hasMessageContaining("not implemented yet");
    }

    @Test
    void numbersAreMaskedInLogs() {
        assertThat(PhoneNumbers.masked("+14035550148")).isEqualTo("+1 403 *** **48");
        assertThat(PhoneNumbers.masked("+33612345678")).isEqualTo("+33 *** **78");
        assertThat(PhoneNumbers.masked("403")).isEqualTo("(invalid number)");
        assertThat(SpokenLanguage.of(java.util.Locale.CANADA_FRENCH).pollyVoice())
                .isEqualTo("Chantal");
    }
}
