package ca.northline.catalogue.adapters.commerce;

import ca.northline.catalogue.application.CommerceCatalogSource;
import ca.northline.catalogue.application.CommerceSettings;
import ca.northline.catalogue.application.ImageFetcher;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.shared.integration.Backoff;
import java.time.Clock;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The {@link CommerceCatalogSource}s for {@code northline.commerce.provider} ({@code COMMERCE_PROVIDER}): {@code local}
 * = the fakes with fixture catalogues (default; refused under {@code staging}/{@code prod}); {@code oauth} = Shopify,
 * Square and Lightspeed, each offered once its app credentials are set (otherwise the Studio shows "Not available
 * yet").
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CommerceProperties.class)
class CommerceConfig {

    @Bean
    CommerceSettings commerceSettings(CommerceProperties p) {
        return new CommerceSettings(p.apiUrl(), p.studioUrl(), p.pollInterval(), p.reconcileInterval());
    }

    /** Not a bean: each integration module keeps its own retry settings. */
    private static Backoff backoff(CommerceProperties p, Clock clock) {
        return new Backoff(p.maxRetries(), p.maxBackoff(), Backoff.THREAD_SLEEP, clock);
    }

    @Bean
    ImageFetcher commerceImageFetcher(CommerceProperties p, Environment env) {
        return local(p, env) ? new LocalImageFetcher() : new HttpImageFetcher(p.images());
    }

    @Bean
    CommerceCatalogSource shopifyCatalogSource(CommerceProperties p, Clock clock, Environment env) {
        if (local(p, env)) {
            return new FakeCatalogSource(CommerceProvider.SHOPIFY, clock);
        }
        log.info(
                "Commerce sync: Shopify {}",
                p.shopify().configured() ? "configured" : "not configured (SHOPIFY_CLIENT_ID)");
        return new ShopifyCatalogSource(
                p.shopify(), CommerceHttp.client(ShopifyCatalogSource.Api.class), backoff(p, clock));
    }

    @Bean
    CommerceCatalogSource squareCatalogSource(CommerceProperties p, Clock clock, Environment env) {
        if (local(p, env)) {
            return new FakeCatalogSource(CommerceProvider.SQUARE, clock);
        }
        log.info(
                "Commerce sync: Square {}",
                p.square().configured() ? "configured" : "not configured (SQUARE_CLIENT_ID)");
        return new SquareCatalogSource(
                p.square(), CommerceHttp.client(SquareCatalogSource.Api.class), backoff(p, clock), clock);
    }

    @Bean
    CommerceCatalogSource lightspeedCatalogSource(CommerceProperties p, Clock clock, Environment env) {
        if (local(p, env)) {
            return new FakeCatalogSource(CommerceProvider.LIGHTSPEED, clock);
        }
        log.info(
                "Commerce sync: Lightspeed {}",
                p.lightspeed().configured() ? "configured" : "not configured (LIGHTSPEED_CLIENT_ID)");
        return new LightspeedCatalogSource(
                p.lightspeed(), CommerceHttp.client(LightspeedCatalogSource.Api.class), backoff(p, clock), clock);
    }

    static boolean local(CommerceProperties p, Environment env) {
        return switch (p.effectiveProvider()) {
            case "local" -> {
                if (env.matchesProfiles("staging | prod")) {
                    throw new IllegalStateException("COMMERCE_PROVIDER=local is not allowed under staging/prod: set"
                            + " COMMERCE_PROVIDER=oauth (docs/runbooks/commerce-sync.md)");
                }
                yield true;
            }
            case "oauth" -> false;
            default ->
                throw new IllegalStateException(
                        "COMMERCE_PROVIDER must be local or oauth, not " + Objects.requireNonNull(p.provider()));
        };
    }
}
