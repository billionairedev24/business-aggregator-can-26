package ca.northline.region.adapters;

import ca.northline.region.application.PlacesAutocomplete;
import java.net.http.HttpClient;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * The {@link PlacesAutocomplete} for {@code northline.places.provider} ({@code PLACES_PROVIDER}): {@code local} = the
 * fixture addresses (default; refused under {@code staging}/{@code prod}), {@code google} = Places API (New) and the
 * Geocoding API with {@code GOOGLE_MAPS_API_KEY} (required then).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlacesProperties.class)
class PlacesConfig {

    @Bean
    PlacesAutocomplete placesAutocomplete(PlacesProperties p, Environment env) {
        return switch (p.effectiveProvider()) {
            case "local" -> {
                if (env.matchesProfiles("staging | prod")) {
                    throw new IllegalStateException("PLACES_PROVIDER=local is not allowed under staging/prod: set"
                            + " PLACES_PROVIDER=google and GOOGLE_MAPS_API_KEY (docs/runbooks/google-maps.md)");
                }
                log.info("Places: local fixture addresses (PLACES_PROVIDER=local)");
                yield new FakePlaces();
            }
            case "google" -> {
                if (p.key().isEmpty()) {
                    throw new IllegalStateException(
                            "PLACES_PROVIDER=google needs GOOGLE_MAPS_API_KEY (docs/runbooks/google-maps.md)");
                }
                log.info("Places: Google Places API (New) + Geocoding API");
                yield new GooglePlaces(p, client(p));
            }
            default ->
                throw new IllegalStateException(
                        "PLACES_PROVIDER must be local or google, not " + Objects.requireNonNull(p.provider()));
        };
    }

    /** JDK client, HTTP/1.1 (plain-http stand-ins), short timeouts: a slow suggestion is a useless one. */
    static GooglePlaces.Api client(PlacesProperties p) {
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(p.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        requests.setReadTimeout(p.timeout());
        var rest = RestClient.builder().requestFactory(requests).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest))
                .build()
                .createClient(GooglePlaces.Api.class);
    }
}
