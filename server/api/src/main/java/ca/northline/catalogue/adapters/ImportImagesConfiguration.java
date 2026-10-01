package ca.northline.catalogue.adapters;

import ca.northline.catalogue.application.RemoteImages;
import ca.northline.platform.EgressPolicy;
import ca.northline.platform.HostResolver;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * S-72: image URLs in bulk-import files are fetched by {@link SafeRemoteImages}. {@code IMPORT_IMAGES_ALLOW_LOCAL=true}
 * also allows {@code http://} and loopback (a local image server, the tests' WireMock) — never private or metadata
 * addresses — and the cloud profiles (dev, staging, prod) refuse to start with it, like {@code WEBHOOKS_ALLOW_LOCAL}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImportImagesConfiguration.ImportImages.class)
class ImportImagesConfiguration {

    /** {@code northline.catalogue.import-images.*} */
    @ConfigurationProperties("northline.catalogue.import-images")
    record ImportImages(@DefaultValue("false") boolean allowLocal) {}

    @Bean
    RemoteImages remoteImages(ImportImages props, Environment env) {
        if (props.allowLocal() && env.matchesProfiles("cloud")) {
            throw new IllegalStateException(
                    "IMPORT_IMAGES_ALLOW_LOCAL=true is not allowed outside local development (S-33 SSRF rules)");
        }
        return new SafeRemoteImages(new EgressPolicy(props.allowLocal()), HostResolver.SYSTEM);
    }
}
