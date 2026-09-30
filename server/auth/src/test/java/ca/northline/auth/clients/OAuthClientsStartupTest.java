package ca.northline.auth.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.auth.AuthServerApplication;
import ca.northline.platform.MissingEnvironmentException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Under prod the studio-bff and consumer-bff (S-45) secret hashes are required before start-up; the not-yet-built
 * console's is not.
 */
class OAuthClientsStartupTest {

    @Test
    void prodWithoutTheStudioSecretHash_failsFast() {
        var app = new SpringApplicationBuilder(AuthServerApplication.class)
                .profiles("prod")
                .web(WebApplicationType.NONE)
                .properties("logging.level.root=off");

        assertThatThrownBy(app::run)
                .isInstanceOf(MissingEnvironmentException.class)
                .satisfies(e -> assertThat(
                                ((MissingEnvironmentException) e).missing().get("secrets"))
                        .contains("STUDIO_BFF_SECRET_HASH", "CONSUMER_BFF_SECRET_HASH")
                        .doesNotContain("CONSOLE_BFF_SECRET_HASH"));
    }
}
