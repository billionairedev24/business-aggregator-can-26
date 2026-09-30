package ca.northline.food.adapters.pos;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.food.application.PosMenuSource;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** {@code POS_PROVIDER}: the fakes by default, the real adapters for {@code oauth}, {@code local} refused in prod. */
class PosConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PosConfig.class)
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void local_isTheDefault() {
        runner.withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(
                                context.getBeansOfType(PosMenuSource.class).values())
                        .hasSize(3)
                        .allSatisfy(s -> assertThat(s).isInstanceOf(FakePosSource.class)));
    }

    @Test
    void oauth_offersEachPosOnceItsCredentialsAreSet() {
        runner.withPropertyValues(
                        "northline.pos.provider=oauth",
                        "northline.pos.toast.client-id=fake-toast",
                        "northline.pos.toast.client-secret=fake-toast-secret")
                .run(context -> {
                    var sources = context.getBeansOfType(PosMenuSource.class).values();
                    assertThat(sources)
                            .filteredOn(s -> s instanceof ToastPosSource)
                            .singleElement()
                            .satisfies(s -> assertThat(s.available()).isTrue());
                    assertThat(sources)
                            .filteredOn(s -> s instanceof SquarePosSource || s instanceof CloverPosSource)
                            .hasSize(2)
                            .allSatisfy(s -> assertThat(s.available()).isFalse());
                });
    }

    @Test
    void local_isRefusedUnderProd() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("POS_PROVIDER=local is not allowed under staging/prod"));
    }
}
