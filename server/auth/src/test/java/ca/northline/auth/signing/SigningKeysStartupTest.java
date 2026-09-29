package ca.northline.auth.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.auth.AuthServerApplication;
import ca.northline.platform.MissingEnvironmentException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** {@code prod} without KMS settings stops before any bean exists (S-1's required-environment check). */
class SigningKeysStartupTest {

    @Test
    void prodWithoutKms_failsFast_listingTheKmsVariables() {
        var app = new SpringApplicationBuilder(AuthServerApplication.class)
                .profiles("prod")
                .web(WebApplicationType.NONE)
                .properties("logging.level.root=off");

        assertThatThrownBy(app::run)
                .isInstanceOf(MissingEnvironmentException.class)
                .satisfies(e -> assertThat(((MissingEnvironmentException) e).missing())
                        .containsEntry("signing-provider", List.of("KMS_PROVIDER"))
                        .containsEntry("signing-key", List.of("KMS_KEY_ID")));
    }

    @Test
    void staging_requiresTheKmsKeyToo() {
        var app = new SpringApplicationBuilder(AuthServerApplication.class)
                .profiles("staging")
                .web(WebApplicationType.NONE)
                .properties("logging.level.root=off", "KMS_PROVIDER=aws");

        assertThatThrownBy(app::run)
                .isInstanceOf(MissingEnvironmentException.class)
                .satisfies(e -> assertThat(((MissingEnvironmentException) e).missing())
                        .containsEntry("signing-key", List.of("KMS_KEY_ID"))
                        .doesNotContainKey("signing-provider"));
    }
}
