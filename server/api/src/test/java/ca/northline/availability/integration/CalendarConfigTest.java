package ca.northline.availability.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.availability.application.CalendarGateway;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** {@code CALENDAR_PROVIDER}: the fakes by default, the real adapters for {@code oauth}, {@code local} refused in prod. */
class CalendarConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CalendarConfig.class)
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void local_isTheDefault() {
        runner.withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(
                                context.getBeansOfType(CalendarGateway.class).values())
                        .hasSize(2)
                        .allSatisfy(g -> assertThat(g).isInstanceOf(FakeCalendarGateway.class)));
    }

    @Test
    void oauth_offersEachProviderOnceItsClientIsSet() {
        runner.withPropertyValues(
                        "northline.calendar.provider=oauth",
                        "northline.calendar.google.client-id=test-google-client",
                        "northline.calendar.google.client-secret=fake-google-secret")
                .run(context -> {
                    var gateways = context.getBeansOfType(CalendarGateway.class).values();
                    assertThat(gateways)
                            .filteredOn(g -> g instanceof GoogleCalendarGateway)
                            .singleElement()
                            .satisfies(g -> assertThat(g.available()).isTrue());
                    assertThat(gateways)
                            .filteredOn(g -> g instanceof MicrosoftCalendarGateway)
                            .singleElement()
                            .satisfies(g -> assertThat(g.available()).isFalse());
                });
    }

    @Test
    void local_isRefusedUnderProd_andUnknownProvidersStopStartUp() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("CALENDAR_PROVIDER=local is not allowed under staging/prod"));
        runner.withPropertyValues("northline.calendar.provider=icloud")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("must be local or oauth"));
    }
}
