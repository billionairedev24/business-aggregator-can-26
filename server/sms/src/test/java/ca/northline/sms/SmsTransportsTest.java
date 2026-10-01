package ca.northline.sms;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.sms.aws.AwsSmsTransport;
import ca.northline.sms.twilio.TwilioSmsTransport;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** {@code northline.sms.provider} picks one transport; the S-8 rules (missing settings, `local` in prod) hold. */
@ExtendWith(OutputCaptureExtension.class)
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
    void localWritesTheTextOnlyOnADevelopersMachine(CapturedOutput output) {
        runner.withPropertyValues("spring.profiles.active=local").run(ctx -> ctx.getBean(SmsTransport.class)
                .sendText("+15875550101", "Join Prairie Wrench: https://studio.example/invite/tok_local_visible"));
        assertThat(output.getOut()).contains("tok_local_visible");

        // S-112: dev keeps the fake but ships its logs — the text (invitation links, codes) never reaches them.
        runner.withPropertyValues("spring.profiles.active=dev").run(ctx -> {
            var transport = ctx.getBean(SmsTransport.class);
            transport.sendText("+15875550101", "Join Prairie Wrench: https://studio.example/invite/tok_dev_hidden");
            transport.call("+15875550101", "Your code is 4 8 2 9 1 3", Locale.CANADA);
        });
        assertThat(output.getOut()).doesNotContain("tok_dev_hidden", "4 8 2 9 1 3").contains("text withheld");
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
        runner.withPropertyValues("northline.sms.provider=azure")
                .run(ctx -> assertThat(ctx)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("not implemented yet"));
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
