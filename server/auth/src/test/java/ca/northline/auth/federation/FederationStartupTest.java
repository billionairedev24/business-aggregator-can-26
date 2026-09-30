package ca.northline.auth.federation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.auth.AuthServerApplication;
import ca.northline.platform.MissingEnvironmentException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** staging (and prod) need the Google and Apple registrations before start-up (S-18); dev doesn't. */
class FederationStartupTest {

    @Test
    void stagingWithoutGoogleAndApple_failsFast() {
        var app = new SpringApplicationBuilder(AuthServerApplication.class)
                .profiles("staging")
                .web(WebApplicationType.NONE)
                .properties("logging.level.root=off");

        assertThatThrownBy(app::run)
                .isInstanceOf(MissingEnvironmentException.class)
                .satisfies(e -> assertThat(((MissingEnvironmentException) e).missing())
                        .containsEntry(
                                "federation",
                                List.of(
                                        "GOOGLE_CLIENT_ID",
                                        "GOOGLE_CLIENT_SECRET",
                                        "APPLE_CLIENT_ID",
                                        "APPLE_TEAM_ID",
                                        "APPLE_KEY_ID",
                                        "APPLE_PRIVATE_KEY")));
    }

    @Test
    void devDoesntNeedThem() {
        var app = new SpringApplicationBuilder(AuthServerApplication.class)
                .profiles("dev")
                .web(WebApplicationType.NONE)
                .properties("logging.level.root=off");

        assertThatThrownBy(app::run)
                .isInstanceOf(MissingEnvironmentException.class)
                .satisfies(e ->
                        assertThat(((MissingEnvironmentException) e).missing()).doesNotContainKey("federation"));
    }
}
