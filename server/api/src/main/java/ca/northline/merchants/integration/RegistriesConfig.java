package ca.northline.merchants.integration;

import ca.northline.merchants.application.BusinessRegistry;
import ca.northline.merchants.domain.RegistrySource;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * One {@link BusinessRegistry} per source, chosen by {@code northline.registries.<source>.provider}
 * (docs/runbooks/registries.md):
 *
 * <ul>
 *   <li>{@code corporations-canada}: {@code fixtures} · {@code api} (ISED Federal Corporation API; URL + key) ·
 *       {@code manual}
 *   <li>{@code alberta}: {@code fixtures} · {@code opencorporates} (search service; key) · {@code manual}
 *       (registry-agent searches by an agent)
 *   <li>{@code calgary}: {@code fixtures} · {@code socrata} (Open Calgary; app token optional) · {@code manual}
 * </ul>
 *
 * {@code fixtures} is the default and is refused under {@code staging}/{@code prod} (a warning under {@code dev}); a
 * provider missing its settings stops start-up naming the variable.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RegistriesConfig.RegistriesProperties.class)
class RegistriesConfig {

    /**
     * @param keyHeader the API Store's key header (Corporations Canada), default {@code user-key}
     * @param dataset the Socrata dataset id (Calgary), default {@code vdjc-pybd}
     */
    record Source(
            @Nullable String provider,
            @Nullable String url,
            @Nullable String key,
            @Nullable String keyHeader,
            @Nullable String dataset) {

        String effective() {
            return provider == null || provider.isBlank()
                    ? "fixtures"
                    : provider.strip().toLowerCase(Locale.ROOT);
        }
    }

    @ConfigurationProperties("northline.registries")
    record RegistriesProperties(
            @Nullable Source corporationsCanada,
            @Nullable Source alberta,
            @Nullable Source calgary) {}

    private static final Source NONE = new Source(null, null, null, null, null);

    @Bean
    BusinessRegistry corporationsCanadaRegistry(RegistriesProperties p, Environment env) {
        var s = p.corporationsCanada() == null ? NONE : p.corporationsCanada();
        return switch (choose("corporations-canada", s, env)) {
            case "api" -> {
                var url = required(s.url(), "REGISTRY_CORPORATIONS_CANADA_URL");
                var key = required(s.key(), "REGISTRY_CORPORATIONS_CANADA_KEY");
                var header = s.keyHeader() == null || s.keyHeader().isBlank()
                        ? "user-key"
                        : s.keyHeader().strip();
                yield new CorporationsCanadaRegistry(
                        RegistryHttp.client(CorporationsCanadaRegistry.Api.class, url, h -> h.set(header, key)),
                        "https://ised-isde.canada.ca/cc/lgcy/fdrlCrpDtls.html?corpId=");
            }
            case "manual" -> new ManualRegistry(RegistrySource.CORPORATIONS_CANADA);
            default -> new FixtureBusinessRegistry(RegistrySource.CORPORATIONS_CANADA);
        };
    }

    @Bean
    BusinessRegistry albertaRegistry(RegistriesProperties p, Environment env) {
        var s = p.alberta() == null ? NONE : p.alberta();
        return switch (choose("alberta", s, env)) {
            case "opencorporates" -> {
                var url = s.url() == null || s.url().isBlank() ? "https://api.opencorporates.com" : s.url();
                yield new OpenCorporatesAlbertaRegistry(
                        RegistryHttp.client(OpenCorporatesAlbertaRegistry.Api.class, url, _ -> {}),
                        required(s.key(), "REGISTRY_ALBERTA_KEY"));
            }
            case "manual" -> new ManualRegistry(RegistrySource.ALBERTA_CORPORATE_REGISTRY);
            default -> new FixtureBusinessRegistry(RegistrySource.ALBERTA_CORPORATE_REGISTRY);
        };
    }

    @Bean
    BusinessRegistry calgaryRegistry(RegistriesProperties p, Environment env) {
        var s = p.calgary() == null ? NONE : p.calgary();
        return switch (choose("calgary", s, env)) {
            case "socrata" -> {
                var url = s.url() == null || s.url().isBlank()
                        ? "https://data.calgary.ca"
                        : s.url().strip();
                var dataset = s.dataset() == null || s.dataset().isBlank()
                        ? "vdjc-pybd"
                        : s.dataset().strip();
                var token = s.key();
                yield new CalgaryBusinessLicences(
                        RegistryHttp.client(CalgaryBusinessLicences.Api.class, url, h -> {
                            if (token != null && !token.isBlank()) {
                                h.set("X-App-Token", token);
                            }
                        }),
                        dataset,
                        url);
            }
            case "manual" -> new ManualRegistry(RegistrySource.CALGARY_BUSINESS_LICENCES);
            default -> new FixtureBusinessRegistry(RegistrySource.CALGARY_BUSINESS_LICENCES);
        };
    }

    private static String choose(String name, Source s, Environment env) {
        var provider = s.effective();
        var allowed = switch (name) {
            case "corporations-canada" -> java.util.Set.of("fixtures", "api", "manual");
            case "alberta" -> java.util.Set.of("fixtures", "opencorporates", "manual");
            default -> java.util.Set.of("fixtures", "socrata", "manual");
        };
        if (!allowed.contains(provider)) {
            throw new IllegalStateException(
                    "Unknown provider '" + provider + "' for registry " + name + " (expected " + allowed + ")");
        }
        if (provider.equals("fixtures")) {
            if (env.acceptsProfiles(Profiles.of("staging", "prod"))) {
                throw new IllegalStateException("Registry " + name
                        + ": provider 'fixtures' is refused under staging/prod (docs/runbooks/registries.md)");
            }
            if (env.acceptsProfiles(Profiles.of("dev"))) {
                log.warn("Registry {} answers from fixtures under dev", name);
            }
        }
        log.info("Registry {}: {}", name, provider);
        return provider;
    }

    private static String required(@Nullable String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + " is not set (docs/runbooks/registries.md)");
        }
        return value.strip();
    }
}
