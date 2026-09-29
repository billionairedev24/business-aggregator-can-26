package ca.northline.auth.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.auth.AuthServerApplication;
import ca.northline.platform.MissingEnvironmentException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** staging/prod list the SMS provider variables among the ones they need before start-up (S-1 mechanism). */
class SmsStartupTest {

    @Test
    void prodWithoutAnSmsProvider_failsFast() {
        var app = new SpringApplicationBuilder(AuthServerApplication.class)
                .profiles("prod")
                .web(WebApplicationType.NONE)
                .properties("logging.level.root=off");

        assertThatThrownBy(app::run)
                .isInstanceOf(MissingEnvironmentException.class)
                .satisfies(e -> assertThat(((MissingEnvironmentException) e).missing())
                        .containsEntry("sms-provider", List.of("SMS_PROVIDER", "SMS_FROM")));
    }
}
