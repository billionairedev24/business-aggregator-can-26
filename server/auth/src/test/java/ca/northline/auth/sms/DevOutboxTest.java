package ca.northline.auth.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import ca.northline.auth.application.SmsSender;
import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PhoneNumber;
import java.time.Clock;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * S-117: the local SMS fake's outbox (read by the end-to-end suite) exists under {@code local} only — never under
 * {@code test}, {@code dev}, {@code staging} or {@code prod}, where neither the bean nor the route is created.
 */
class DevOutboxTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(SmsConfig.class, DevOutboxController.class);

    private static final String[] TWILIO = {
        "northline.sms.provider=twilio",
        "northline.sms.account-id=ACtest-account-sid",
        "northline.sms.auth-token=secret",
        "northline.sms.from=+15875550100"
    };

    @Test
    void local_keepsEveryCodeForTheNumber_newestFirst() {
        var to = PhoneNumber.parse("+1 587 555 0101").orElseThrow();
        runner.withPropertyValues("spring.profiles.active=local").run(ctx -> {
            assertThat(ctx).hasSingleBean(DevOutbox.class).hasSingleBean(DevOutboxController.class);
            var sms = ctx.getBean(SmsSender.class);
            sms.sendCode(to, "482913", Channel.SMS, Locale.CANADA);
            sms.sendCode(PhoneNumber.parse("+1 587 555 0199").orElseThrow(), "111111", Channel.SMS, Locale.CANADA);
            sms.sendCode(to, "731264", Channel.VOICE, Locale.CANADA_FRENCH);
            assertThat(ctx.getBean(DevOutbox.class).to("(587) 555-0101"))
                    .extracting(DevOutbox.Sent::code, DevOutbox.Sent::channel, DevOutbox.Sent::to)
                    .containsExactly(tuple("731264", "voice", "+15875550101"), tuple("482913", "sms", "+15875550101"));
        });
    }

    @Test
    void absentUnderEveryOtherProfile() {
        for (var profile : new String[] {"test", "dev", "dev,cloud"}) {
            runner.withPropertyValues("spring.profiles.active=" + profile)
                    .run(ctx -> assertThat(ctx)
                            .hasNotFailed()
                            .doesNotHaveBean(DevOutbox.class)
                            .doesNotHaveBean(DevOutboxController.class));
        }
        for (var profile : new String[] {"staging", "prod"}) {
            runner.withPropertyValues(TWILIO)
                    .withPropertyValues("spring.profiles.active=" + profile + ",cloud")
                    .run(ctx -> assertThat(ctx)
                            .hasNotFailed()
                            .doesNotHaveBean(DevOutbox.class)
                            .doesNotHaveBean(DevOutboxController.class));
        }
    }

    @Test
    void theOutboxIsBounded() {
        var outbox = new DevOutbox(Clock.systemUTC());
        for (int i = 0; i < DevOutbox.CAPACITY + 50; i++) {
            outbox.record("+15875550101", "sms", "%06d".formatted(i));
        }
        assertThat(outbox.to("+15875550101"))
                .hasSize(DevOutbox.CAPACITY)
                .first()
                .satisfies(s -> assertThat(s.code()).isEqualTo("%06d".formatted(DevOutbox.CAPACITY + 49)));
    }
}
