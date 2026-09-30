package ca.northline.catalogue.adapters.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.catalogue.application.CommerceCatalogSource;
import ca.northline.catalogue.application.ImageFetcher;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** {@code COMMERCE_PROVIDER}: the fakes by default, the real adapters for {@code oauth}, {@code local} refused in prod. */
class CommerceConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CommerceConfig.class)
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void local_isTheDefault() {
        runner.withPropertyValues("spring.profiles.active=test").run(context -> {
            assertThat(context.getBeansOfType(CommerceCatalogSource.class).values())
                    .hasSize(3)
                    .allSatisfy(s -> assertThat(s).isInstanceOf(FakeCatalogSource.class));
            assertThat(context.getBean(ImageFetcher.class)).isInstanceOf(LocalImageFetcher.class);
        });
    }

    @Test
    void oauth_offersEachPlatformOnceItsAppIsSet() {
        runner.withPropertyValues(
                        "northline.commerce.provider=oauth",
                        "northline.commerce.square.client-id=sq0idp-fake",
                        "northline.commerce.square.client-secret=sq0csp-fake")
                .run(context -> {
                    var sources =
                            context.getBeansOfType(CommerceCatalogSource.class).values();
                    assertThat(sources)
                            .filteredOn(s -> s instanceof SquareCatalogSource)
                            .singleElement()
                            .satisfies(s -> assertThat(s.available()).isTrue());
                    assertThat(sources)
                            .filteredOn(s -> s instanceof ShopifyCatalogSource || s instanceof LightspeedCatalogSource)
                            .hasSize(2)
                            .allSatisfy(s -> assertThat(s.available()).isFalse());
                    assertThat(context.getBean(ImageFetcher.class)).isInstanceOf(HttpImageFetcher.class);
                });
    }

    @Test
    void local_isRefusedUnderProd_andUnknownProvidersStopStartUp() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("COMMERCE_PROVIDER=local is not allowed under staging/prod"));
        runner.withPropertyValues("northline.commerce.provider=woocommerce")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("must be local or oauth"));
    }
}
