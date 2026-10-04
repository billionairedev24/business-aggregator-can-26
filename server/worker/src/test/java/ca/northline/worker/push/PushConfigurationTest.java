package ca.northline.worker.push;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** S-102, owner decision 2026-10-04: staging refuses the log-only push sender, as prod does; dev may keep it. */
class PushConfigurationTest {

    private static PushProperties local() {
        return new PushProperties(
                PushProperties.Provider.LOCAL,
                new PushProperties.Apns(
                        "https://api.push.apple.com", null, null, null, "ca.northline.app", "ca.northline.courier"),
                new PushProperties.Fcm("https://fcm.googleapis.com", null, null),
                Duration.ofDays(90),
                Duration.ofSeconds(30),
                Duration.ofMinutes(15),
                Duration.ofSeconds(10));
    }

    private static MockEnvironment profiles(String... names) {
        var env = new MockEnvironment();
        env.setActiveProfiles(names);
        return env;
    }

    @Test
    void stagingAndProdRefuseTheLogOnlySender() {
        for (var profile : new String[] {"staging", "prod"}) {
            assertThatThrownBy(() -> new PushConfiguration(local(), profiles("cloud", profile)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PUSH_PROVIDER=local is not allowed under staging/prod");
        }
    }

    @Test
    void devAndLocalMayKeepIt() {
        assertThatCode(() -> new PushConfiguration(local(), profiles("cloud", "dev")))
                .doesNotThrowAnyException();
        assertThatCode(() -> new PushConfiguration(local(), profiles("local"))).doesNotThrowAnyException();
    }
}
